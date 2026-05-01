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
            scope.launch {
                runItem(job.id, item.id, attempt, command, timeout)
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

    suspend fun cancelJob(
        id: ScriptJobId,
        cancellation: ScriptJobCancellation = ScriptJobCancellation(),
    ): ScriptJob? {
        return repository.cancelJob(id, cancellation)
    }

    suspend fun cancelItem(
        id: ScriptJobId,
        itemId: ScriptJobItemId,
        cancellation: ScriptJobCancellation = ScriptJobCancellation(),
    ): ScriptJobItem? {
        return repository.cancelItem(id, itemId, cancellation)
    }

    suspend fun resumeIncompleteJobs(
        timeout: Duration = 3.seconds,
        limit: Int = 100,
    ): List<ScriptJob> {
        require(limit > 0) { "script job resume limit must be positive" }
        val jobs = repository.listRecoverableJobs(limit)
        jobs.forEach { job ->
            scope.launch {
                resumeJob(job.id, timeout)
            }
        }
        return jobs
    }

    private suspend fun resumeJob(jobId: ScriptJobId, timeout: Duration) {
        repository.expireLeasedRunningItems(jobId)
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
            claimed.forEach { item ->
                val current = repository.findItem(jobId, item.id) ?: item
                val attempt = current.attempts.size + 1
                val command = current.command(job, attempt)
                repository.markItemRunning(
                    jobId = job.id,
                    itemId = current.id,
                    attempt = attempt,
                    command = command,
                    leaseOwner = workerId,
                    leaseUntilMillis = current.leaseUntilMillis ?: leaseUntilMillis(timeout),
                )
                runItem(job.id, current.id, attempt, command, timeout)
            }
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
    ) {
        tracer.span("script.job.item.run", jobTraceAttributes(jobId, command)) {
            metrics.counter("asteria.script.job.item.running.total", command.metricTags()).increment()
            val heartbeat = startLeaseHeartbeat(jobId, itemId, attempt, timeout)
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
                repository.markItemFinished(
                    jobId = jobId,
                    itemId = itemId,
                    attempt = attempt,
                    status = status,
                    results = result.results,
                    error = result.results.firstOrNull { !it.success }?.error,
                )
                metrics.counter(
                    "asteria.script.job.item.finished.total",
                    command.metricTags() + MetricTags.of("status" to status.name),
                ).increment()
            } finally {
                heartbeat.cancel()
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
                "script.sourceExecutionId" to job.command.executionId,
            ),
        )
    }

    private fun ScriptExecutionMetadata.withAttributes(vararg entries: Pair<String, String>): ScriptExecutionMetadata {
        return copy(attributes = attributes + entries)
    }
}
