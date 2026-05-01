package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.github.mikai233.asteria.observability.NoopTracer
import io.github.mikai233.asteria.observability.TraceAttributes
import io.github.mikai233.asteria.observability.Tracer
import io.github.mikai233.asteria.script.ScriptExecutionBatchResult
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionMetadata
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptRuntime
import io.github.mikai233.asteria.script.ScriptTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ScriptJobService(
    private val runtime: ScriptRuntime,
    private val repository: ScriptJobRepository,
    private val scope: CoroutineScope,
    private val tracer: Tracer = NoopTracer,
    private val metrics: Metrics = NoopMetrics,
    private val workerId: String = "script-job-${UUID.randomUUID()}",
    private val claimBatchSize: Int = 64,
    private val leaseDuration: Duration = 30.seconds,
    private val leaseRenewalInterval: Duration = 10.seconds,
    private val executionLimiter: ScriptJobExecutionLimiter = SemaphoreScriptJobExecutionLimiter(),
    private val auditSink: ScriptJobAuditSink = NoopScriptJobAuditSink,
) {
    init {
        require(workerId.isNotBlank()) { "script job worker id must not be blank" }
        require(claimBatchSize > 0) { "script job claim batch size must be positive" }
        require(leaseDuration > Duration.ZERO) { "script job lease duration must be positive" }
        require(leaseRenewalInterval > Duration.ZERO) { "script job lease renewal interval must be positive" }
    }

    suspend fun submit(
        command: ScriptExecutionCommand,
        timeout: Duration = 3.seconds,
        id: ScriptJobId = ScriptJobId(command.executionId),
    ): ScriptJob {
        return tracer.span("script.job.submit", jobTraceAttributes(id, command)) {
            metrics.counter("asteria.script.job.submitted.total", command.metricTags()).increment()
            val job = ScriptJob(id, command)
            val items = command.expandItems(id)
            repository.create(job, items)
            audit(
                ScriptJobAuditEvent(
                    type = ScriptJobAuditEventType.JobSubmitted,
                    jobId = id,
                    operatorId = command.metadata.requester,
                    attributes = command.auditAttributes() + mapOf("itemCount" to items.size.toString()),
                ),
            )
            scope.launch {
                runClaimedItems(job.id, timeout)
            }
            requireNotNull(repository.find(id))
        }
    }

    suspend fun retryItem(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        timeout: Duration = 3.seconds,
        requestedBy: String? = null,
    ): ScriptJobItem {
        val job = requireNotNull(repository.find(jobId)) { "script job $jobId not found" }
        val item = requireNotNull(repository.findItem(jobId, itemId)) { "script job item $itemId not found" }
        require(item.status == ScriptJobItemStatus.Failed) { "script job item $itemId is not failed" }
        val attempt = item.attempts.size + 1
        val command = item.command(job, attempt)
        return tracer.span("script.job.item.retry", jobTraceAttributes(job.id, command)) {
            metrics.counter("asteria.script.job.item.retry.total", command.metricTags()).increment()
            repository.markItemRunning(
                jobId = job.id,
                itemId = item.id,
                attempt = attempt,
                command = command,
                leaseOwner = workerId,
                leaseUntilMillis = leaseUntilMillis(timeout),
            )
            audit(
                ScriptJobAuditEvent(
                    type = ScriptJobAuditEventType.ItemRetryRequested,
                    jobId = job.id,
                    itemId = item.id,
                    attempt = attempt,
                    workerId = workerId,
                    status = ScriptJobItemStatus.Running,
                    operatorId = requestedBy,
                    attributes = command.auditAttributes(),
                ),
            )
            auditItemStarted(job.id, item.id, attempt, command)
            scope.launch {
                limitAndRunItem(job.id, item.id, attempt, command, timeout)
            }
            requireNotNull(repository.findItem(job.id, item.id))
        }
    }

    suspend fun find(id: ScriptJobId): ScriptJob? {
        return repository.find(id)
    }

    suspend fun listJobs(query: ScriptJobQuery = ScriptJobQuery()): ScriptJobPage {
        return repository.listJobs(query)
    }

    suspend fun listItems(id: ScriptJobId, query: ScriptJobItemQuery = ScriptJobItemQuery()): ScriptJobItemPage {
        return repository.listItems(id, query)
    }

    suspend fun findItem(id: ScriptJobId, itemId: ScriptJobItemId): ScriptJobItem? {
        return repository.findItem(id, itemId)
    }

    suspend fun summarizeResults(id: ScriptJobId): ScriptJobResultSummary {
        val job = requireNotNull(repository.find(id)) { "script job $id not found" }
        val items = allItems(id)
        val errors = items
            .asSequence()
            .filter { it.status == ScriptJobItemStatus.Failed }
            .groupBy { it.errorSummary() }
            .map { (error, failedItems) ->
                ScriptJobErrorSummary(
                    error = error,
                    count = failedItems.size,
                    sampleTargets = failedItems.take(5).map { it.target.summary() },
                )
            }
            .sortedByDescending { it.count }
        return ScriptJobResultSummary(
            jobId = id,
            totalItems = job.totalItems,
            completedItems = job.completedItems,
            failedItems = job.failedItems,
            cancelledItems = job.cancelledItems,
            errorTypes = errors,
        )
    }

    suspend fun exportResults(
        id: ScriptJobId,
        status: ScriptJobItemStatus? = null,
    ): ScriptJobResultExport {
        repository.find(id) ?: error("script job $id not found")
        val rows = allItems(id, status)
        val csv = buildString {
            appendLine("itemId,status,target,error,resultCount")
            rows.forEach { item ->
                appendCsvRow(
                    item.id.value,
                    item.status.name,
                    item.target.summary(),
                    item.errorSummary(),
                    item.results.size.toString(),
                )
            }
        }
        return ScriptJobResultExport(
            fileName = "script-job-${id.value}-results.csv",
            contentType = "text/csv",
            content = csv,
        )
    }

    suspend fun retryFailedItems(
        jobId: ScriptJobId,
        request: ScriptJobRetryFailedItemsRequest = ScriptJobRetryFailedItemsRequest(),
        timeout: Duration = 3.seconds,
        requestedBy: String? = null,
    ): List<ScriptJobItem> {
        repository.find(jobId) ?: error("script job $jobId not found")
        val failed = allItems(jobId, ScriptJobItemStatus.Failed)
            .asSequence()
            .filter { item -> request.error == null || item.errorSummary() == request.error }
            .take(request.limit)
            .toList()
        return failed.map { item ->
            retryItem(jobId, item.id, timeout, requestedBy)
        }
    }

    suspend fun cancelJob(
        id: ScriptJobId,
        cancellation: ScriptJobCancellation = ScriptJobCancellation(),
    ): ScriptJob? {
        val job = repository.cancelJob(id, cancellation)
        if (job != null) {
            audit(
                ScriptJobAuditEvent(
                    type = ScriptJobAuditEventType.JobCancelled,
                    jobId = id,
                    operatorId = cancellation.requestedBy,
                    attributes = job.command.auditAttributes() + mapOf(
                        "reason" to (cancellation.reason ?: "none"),
                        "cancelledItems" to job.cancelledItems.toString(),
                    ),
                ),
            )
        }
        return job
    }

    suspend fun cancelItem(
        id: ScriptJobId,
        itemId: ScriptJobItemId,
        cancellation: ScriptJobCancellation = ScriptJobCancellation(),
    ): ScriptJobItem? {
        val item = repository.cancelItem(id, itemId, cancellation)
        if (item != null) {
            audit(
                ScriptJobAuditEvent(
                    type = ScriptJobAuditEventType.ItemCancelRequested,
                    jobId = id,
                    itemId = itemId,
                    status = item.status,
                    operatorId = cancellation.requestedBy,
                    attributes = mapOf("reason" to (cancellation.reason ?: "none")),
                ),
            )
        }
        return item
    }

    suspend fun resumeIncompleteJobs(
        timeout: Duration = 3.seconds,
        limit: Int = 100,
    ): List<ScriptJob> {
        require(limit > 0) { "script job resume limit must be positive" }
        val jobs = repository.listRecoverableJobs(limit)
        jobs.forEach { job ->
            audit(
                ScriptJobAuditEvent(
                    type = ScriptJobAuditEventType.JobResumed,
                    jobId = job.id,
                    attributes = job.command.auditAttributes(),
                ),
            )
            scope.launch {
                resumeJob(job.id, timeout)
            }
        }
        return jobs
    }

    fun startRecoveryLoop(
        timeout: Duration = 3.seconds,
        limit: Int = 100,
        interval: Duration = 30.seconds,
    ): Job {
        require(limit > 0) { "script job recovery limit must be positive" }
        require(interval > Duration.ZERO) { "script job recovery interval must be positive" }
        return scope.launch {
            while (isActive) {
                delay(interval)
                runCatching {
                    val resumed = resumeIncompleteJobs(timeout, limit)
                    metrics.counter("asteria.script.job.recovery.scan.total").increment()
                    if (resumed.isNotEmpty()) {
                        metrics.counter("asteria.script.job.recovery.resumed.total").increment(resumed.size.toLong())
                    }
                }.onFailure {
                    metrics.counter("asteria.script.job.recovery.scan.failed.total").increment()
                }
            }
        }
    }

    private suspend fun resumeJob(jobId: ScriptJobId, timeout: Duration) {
        repository.expireLeasedRunningItems(jobId).forEach { item ->
            audit(
                ScriptJobAuditEvent(
                    type = ScriptJobAuditEventType.ItemExpired,
                    jobId = jobId,
                    itemId = item.id,
                    attempt = item.attempts.lastOrNull()?.attempt,
                    status = item.status,
                    attributes = item.attempts.lastOrNull()?.command?.auditAttributes().orEmpty(),
                ),
            )
        }
        runClaimedItems(jobId, timeout)
    }

    private suspend fun runClaimedItems(jobId: ScriptJobId, timeout: Duration) {
        while (true) {
            val job = repository.find(jobId) ?: return
            val claimed = repository.claimPendingItems(
                id = jobId,
                workerId = workerId,
                limit = claimBatchSize,
                leaseUntilMillis = leaseUntilMillis(timeout),
            )
            if (claimed.isEmpty()) {
                return
            }
            claimed.map { item ->
                scope.launch {
                    startClaimedItem(job, item, timeout)
                }
            }.joinAll()
        }
    }

    private suspend fun startClaimedItem(
        job: ScriptJob,
        item: ScriptJobItem,
        timeout: Duration,
    ) {
        executionLimiter.limit(
            ScriptJobExecutionContext(
                jobId = job.id,
                itemId = item.id,
                attempt = item.attempts.size + 1,
                command = item.command(job, item.attempts.size + 1),
                workerId = workerId,
            ),
        ) {
            val latestJob = repository.find(job.id) ?: return@limit
            val latestItem = repository.findItem(job.id, item.id) ?: item
            if (latestItem.status != ScriptJobItemStatus.Pending) {
                return@limit
            }
            if (
                latestItem.leaseOwner != workerId ||
                latestItem.leaseUntilMillis == null ||
                latestItem.leaseUntilMillis <= System.currentTimeMillis()
            ) {
                return@limit
            }
            val attempt = latestItem.attempts.size + 1
            val command = latestItem.command(latestJob, attempt)
            repository.markItemRunning(
                jobId = latestJob.id,
                itemId = latestItem.id,
                attempt = attempt,
                command = command,
                leaseOwner = workerId,
                leaseUntilMillis = latestItem.leaseUntilMillis,
            )
            auditItemStarted(latestJob.id, latestItem.id, attempt, command)
            runItem(latestJob.id, latestItem.id, attempt, command, timeout)
        }
    }

    private suspend fun limitAndRunItem(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        command: ScriptExecutionCommand,
        timeout: Duration,
    ) {
        val heartbeat = startLeaseHeartbeat(jobId, itemId, attempt, timeout)
        try {
            executionLimiter.limit(
                ScriptJobExecutionContext(
                    jobId = jobId,
                    itemId = itemId,
                    attempt = attempt,
                    command = command,
                    workerId = workerId,
                ),
            ) {
                runItem(jobId, itemId, attempt, command, timeout, manageHeartbeat = false)
            }
        } finally {
            heartbeat.cancel()
        }
    }

    private fun leaseUntilMillis(timeout: Duration): Long {
        return System.currentTimeMillis() + maxOf(leaseDuration, timeout + 5.seconds).inWholeMilliseconds
    }

    private suspend fun runItem(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        command: ScriptExecutionCommand,
        timeout: Duration,
        manageHeartbeat: Boolean = true,
    ) {
        tracer.span("script.job.item.run", jobTraceAttributes(jobId, command)) {
            metrics.counter("asteria.script.job.item.running.total", command.metricTags()).increment()
            val heartbeat = if (manageHeartbeat) startLeaseHeartbeat(jobId, itemId, attempt, timeout) else null
            try {
                val result = metrics.timer("asteria.script.job.item.duration", command.metricTags()).record {
                    runCatching {
                        runtime.executeAll(command, timeout)
                    }.getOrElse {
                        error(it)
                        ScriptExecutionBatchResult(
                            executionId = command.executionId,
                            results = listOf(
                                ScriptExecutionResult(
                                    executionId = command.executionId,
                                    success = false,
                                    error = it.message,
                                ),
                            ),
                        )
                    }
                }
                val status = result.itemStatus()
                val finished = repository.markItemFinished(
                    jobId = jobId,
                    itemId = itemId,
                    attempt = attempt,
                    leaseOwner = workerId,
                    status = status,
                    results = result.results,
                    error = result.results.firstOrNull { !it.success }?.error,
                )
                if (finished) {
                    val finalStatus = repository.findItem(jobId, itemId)?.status ?: status
                    metrics.counter(
                        "asteria.script.job.item.finished.total",
                        command.metricTags() + MetricTags.of("status" to finalStatus.name),
                    ).increment()
                    audit(
                        ScriptJobAuditEvent(
                            type = finalStatus.auditEventType(),
                            jobId = jobId,
                            itemId = itemId,
                            attempt = attempt,
                            workerId = workerId,
                            status = finalStatus,
                            operatorId = command.metadata.requester,
                            attributes = command.auditAttributes() + result.results.auditSummary(),
                        ),
                    )
                } else {
                    metrics.counter(
                        "asteria.script.job.item.stale_finish.total",
                        command.metricTags(),
                    ).increment()
                    audit(
                        ScriptJobAuditEvent(
                            type = ScriptJobAuditEventType.ItemStaleFinishIgnored,
                            jobId = jobId,
                            itemId = itemId,
                            attempt = attempt,
                            workerId = workerId,
                            attributes = command.auditAttributes(),
                        ),
                    )
                }
            } finally {
                heartbeat?.cancel()
            }
        }
    }

    private fun startLeaseHeartbeat(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        timeout: Duration,
    ): Job {
        return scope.launch {
            while (isActive) {
                delay(leaseRenewalInterval)
                val renewed = repository.renewRunningItemLease(
                    jobId = jobId,
                    itemId = itemId,
                    attempt = attempt,
                    leaseOwner = workerId,
                    leaseUntilMillis = leaseUntilMillis(timeout),
                )
                if (!renewed) {
                    return@launch
                }
            }
        }
    }

    private fun ScriptExecutionBatchResult.itemStatus(): ScriptJobItemStatus {
        return if (success) {
            ScriptJobItemStatus.Completed
        } else {
            ScriptJobItemStatus.Failed
        }
    }

    private suspend fun auditItemStarted(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        command: ScriptExecutionCommand,
    ) {
        audit(
            ScriptJobAuditEvent(
                type = ScriptJobAuditEventType.ItemStarted,
                jobId = jobId,
                itemId = itemId,
                attempt = attempt,
                workerId = workerId,
                status = ScriptJobItemStatus.Running,
                operatorId = command.metadata.requester,
                attributes = command.auditAttributes(),
            ),
        )
    }

    private suspend fun audit(event: ScriptJobAuditEvent) {
        auditSink.record(event)
    }

    private fun ScriptJobItemStatus.auditEventType(): ScriptJobAuditEventType {
        return when (this) {
            ScriptJobItemStatus.Completed -> ScriptJobAuditEventType.ItemCompleted
            ScriptJobItemStatus.Failed -> ScriptJobAuditEventType.ItemFailed
            ScriptJobItemStatus.Cancelled -> ScriptJobAuditEventType.ItemCancelled
            ScriptJobItemStatus.Pending,
            ScriptJobItemStatus.Running,
            -> error("script job item status $this is not terminal")
        }
    }

    private fun jobTraceAttributes(id: ScriptJobId, command: ScriptExecutionCommand): TraceAttributes {
        return TraceAttributes.of(
            "script.job_id" to id.value,
            "script.execution_id" to command.executionId,
            "script.target" to command.target.toString(),
            "script.engine" to command.artifact.engine,
        )
    }

    private fun ScriptExecutionCommand.metricTags(): MetricTags {
        return MetricTags.of(
            "target" to target.javaClass.simpleName,
            "engine" to artifact.engine,
        )
    }

    private fun ScriptExecutionCommand.expandItems(jobId: ScriptJobId): List<ScriptJobItem> {
        return target.expand().mapIndexed { index, target ->
            ScriptJobItem(
                id = ScriptJobItemId((index + 1).toString()),
                jobId = jobId,
                target = target,
            )
        }
    }

    private fun ScriptTarget.expand(): List<ScriptTarget> {
        return when (this) {
            ScriptTarget.AllNodes -> listOf(this)
            is ScriptTarget.Role -> listOf(this)
            is ScriptTarget.Node -> addresses.map { ScriptTarget.Node(listOf(it)) }
            is ScriptTarget.ActorPath -> paths.map { ScriptTarget.ActorPath(listOf(it)) }
            is ScriptTarget.Entity -> ids.map { ScriptTarget.Entity(kind, listOf(it)) }
            is ScriptTarget.Singleton -> listOf(this)
        }
    }

    private fun ScriptJobItem.command(job: ScriptJob, attempt: Int): ScriptExecutionCommand {
        return job.command.copy(
            executionId = "${job.command.executionId}.${id.value}.$attempt",
            target = target,
            metadata = job.command.metadata.withAttributes(
                "script.jobId" to job.id.value,
                "script.itemId" to id.value,
                "script.attempt" to attempt.toString(),
                "script.workerId" to workerId,
                "script.sourceExecutionId" to job.command.executionId,
            ),
        )
    }

    private fun ScriptExecutionMetadata.withAttributes(vararg entries: Pair<String, String>): ScriptExecutionMetadata {
        return copy(attributes = attributes + entries)
    }

    private suspend fun allItems(
        id: ScriptJobId,
        status: ScriptJobItemStatus? = null,
        pageSize: Int = 1_000,
    ): List<ScriptJobItem> {
        val items = mutableListOf<ScriptJobItem>()
        var offset = 0
        while (true) {
            val page = repository.listItems(id, ScriptJobItemQuery(status = status, offset = offset, limit = pageSize))
            items += page.items
            offset = page.nextOffset ?: break
        }
        return items
    }

    private fun ScriptJobItem.errorSummary(): String {
        return attempts.lastOrNull()?.error
            ?: results.firstOrNull { !it.success }?.error
            ?: "unknown"
    }

    private fun ScriptTarget.summary(): String {
        return when (this) {
            ScriptTarget.AllNodes -> "all-nodes"
            is ScriptTarget.ActorPath -> paths.joinToString(",")
            is ScriptTarget.Entity -> "${kind.value}:${ids.joinToString(",")}"
            is ScriptTarget.Node -> addresses.joinToString(",")
            is ScriptTarget.Role -> "role:${role.value}"
            is ScriptTarget.Singleton -> "singleton:${name.value}"
        }
    }

    private fun StringBuilder.appendCsvRow(vararg values: String) {
        appendLine(values.joinToString(",") { it.csvEscape() })
    }

    private fun String.csvEscape(): String {
        return if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${replace("\"", "\"\"")}\""
        } else {
            this
        }
    }
}
