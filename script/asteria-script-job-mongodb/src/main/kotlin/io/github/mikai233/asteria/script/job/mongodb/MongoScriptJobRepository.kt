package io.github.mikai233.asteria.script.job.mongodb

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.lte
import com.mongodb.client.model.Filters.or
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Filters.`in` as inFilter
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.set
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionMetadata
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptResourceRef
import io.github.mikai233.asteria.script.ScriptTarget
import io.github.mikai233.asteria.script.job.ScriptJob
import io.github.mikai233.asteria.script.job.ScriptJobCancellation
import io.github.mikai233.asteria.script.job.ScriptJobId
import io.github.mikai233.asteria.script.job.ScriptJobItem
import io.github.mikai233.asteria.script.job.ScriptJobItemAttempt
import io.github.mikai233.asteria.script.job.ScriptJobItemId
import io.github.mikai233.asteria.script.job.ScriptJobItemPage
import io.github.mikai233.asteria.script.job.ScriptJobItemQuery
import io.github.mikai233.asteria.script.job.ScriptJobPage
import io.github.mikai233.asteria.script.job.ScriptJobQuery
import io.github.mikai233.asteria.script.job.ScriptJobItemStatus
import io.github.mikai233.asteria.script.job.ScriptJobRepository
import io.github.mikai233.asteria.script.job.ScriptJobStatus
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.conversions.Bson
import java.util.Base64

/**
 * MongoDB implementation of [ScriptJobRepository].
 *
 * Jobs and items are stored in separate collections so large GM batches do not hit MongoDB's single-document size
 * limit. The repository stores complete command, target, result, and attempt snapshots for auditability.
 */
class MongoScriptJobRepository(
    database: MongoDatabase,
    jobsCollectionName: String = "script_jobs",
    itemsCollectionName: String = "script_job_items",
) : ScriptJobRepository {
    private val jobs: MongoCollection<Document> = database.getCollection(jobsCollectionName)
    private val items: MongoCollection<Document> = database.getCollection(itemsCollectionName)

    /**
     * Creates the indexes expected by the repository.
     *
     * Applications can call this during infrastructure initialization. It is not called implicitly because index
     * creation policy usually belongs to deployment tooling in production.
     */
    suspend fun ensureIndexes() {
        jobs.createIndex(Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("createdAtMillis")))
        items.createIndex(
            Indexes.compoundIndex(Indexes.ascending("jobId"), Indexes.ascending("itemId")),
            IndexOptions().unique(true),
        )
        items.createIndex(Indexes.compoundIndex(Indexes.ascending("jobId"), Indexes.ascending("status")))
        items.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending("jobId"),
                Indexes.ascending("status"),
                Indexes.ascending("leaseUntilMillis"),
            ),
        )
    }

    override suspend fun create(job: ScriptJob, items: List<ScriptJobItem>) {
        require(items.isNotEmpty()) { "script job items must not be empty" }
        val storedJob = job.copy(totalItems = items.size)
        jobs.insertOne(storedJob.toDocument())
        this.items.insertMany(items.map { it.toDocument() })
    }

    override suspend fun claimPendingItems(
        id: ScriptJobId,
        workerId: String,
        limit: Int,
        leaseUntilMillis: Long,
        nowMillis: Long,
    ): List<ScriptJobItem> {
        require(workerId.isNotBlank()) { "script job worker id must not be blank" }
        require(limit > 0) { "script job claim limit must be positive" }
        require(leaseUntilMillis > nowMillis) { "script job item lease must be in the future" }
        val availableFilter = and(
            eq("jobId", id.value),
            eq("status", ScriptJobItemStatus.Pending.name),
            leaseAvailable(nowMillis),
        )
        val candidates = items.find(availableFilter)
            .sort(Sorts.ascending("itemId"))
            .limit(limit)
            .toList()
        val claimed = mutableListOf<ScriptJobItem>()
        for (candidate in candidates) {
            val itemId = candidate.requiredString("itemId")
            val itemFilter = and(
                eq("jobId", id.value),
                eq("itemId", itemId),
                eq("status", ScriptJobItemStatus.Pending.name),
                leaseAvailable(nowMillis),
            )
            val result = items.updateOne(
                itemFilter,
                combine(
                    set("leaseOwner", workerId),
                    set("leaseUntilMillis", leaseUntilMillis),
                    set("updatedAtMillis", nowMillis),
                ),
            )
            if (result.matchedCount == 1L) {
                findItem(id, ScriptJobItemId(itemId))?.let { claimed += it }
            }
        }
        return claimed
    }

    override suspend fun markItemRunning(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        command: ScriptExecutionCommand,
        leaseOwner: String,
        leaseUntilMillis: Long,
    ) {
        require(attempt > 0) { "script job item attempt must be greater than 0" }
        require(leaseOwner.isNotBlank()) { "script job item lease owner must not be blank" }
        val item = requireNotNull(findItem(jobId, itemId)) { "script job item $itemId not found" }
        require(attempt == item.attempts.size + 1) {
            "script job item $itemId expected attempt ${item.attempts.size + 1}, got $attempt"
        }
        require(item.status == ScriptJobItemStatus.Pending || item.status == ScriptJobItemStatus.Failed) {
            "script job item $itemId cannot start from status ${item.status}"
        }
        val now = System.currentTimeMillis()
        require(leaseUntilMillis > now) { "script job item lease must be in the future" }
        val updated = item.copy(
            status = ScriptJobItemStatus.Running,
            attempts = item.attempts + ScriptJobItemAttempt(
                attempt = attempt,
                command = command,
                status = ScriptJobItemStatus.Running,
                startedAtMillis = now,
            ),
            leaseOwner = leaseOwner,
            leaseUntilMillis = leaseUntilMillis,
            updatedAtMillis = now,
        )
        replaceItem(updated)
        refreshJob(jobId, now)
    }

    override suspend fun markItemFinished(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        status: ScriptJobItemStatus,
        results: List<ScriptExecutionResult>,
        error: String?,
    ) {
        require(status == ScriptJobItemStatus.Completed || status == ScriptJobItemStatus.Failed) {
            "script job item finish status must be terminal"
        }
        val item = requireNotNull(findItem(jobId, itemId)) { "script job item $itemId not found" }
        require(item.attempts.isNotEmpty()) { "script job item $itemId has no running attempt" }
        require(item.attempts.last().attempt == attempt) {
            "script job item $itemId latest attempt is ${item.attempts.last().attempt}, got $attempt"
        }
        val now = System.currentTimeMillis()
        val finalStatus = item.finalStatus(status)
        val updatedAttempt = item.attempts.last().copy(
            status = finalStatus,
            results = results,
            error = error,
            finishedAtMillis = now,
        )
        val updated = item.copy(
            status = finalStatus,
            results = results,
            attempts = item.attempts.dropLast(1) + updatedAttempt,
            leaseOwner = null,
            leaseUntilMillis = null,
            updatedAtMillis = now,
        )
        replaceItem(updated)
        refreshJob(jobId, now)
    }

    override suspend fun expireLeasedRunningItems(
        id: ScriptJobId,
        nowMillis: Long,
        error: String,
    ): List<ScriptJobItem> {
        require(error.isNotBlank()) { "script job item expiration error must not be blank" }
        val candidates = items.find(
            and(
                eq("jobId", id.value),
                eq("status", ScriptJobItemStatus.Running.name),
                lte("leaseUntilMillis", nowMillis),
            ),
        ).toList().map { it.toScriptJobItem() }
        val expired = mutableListOf<ScriptJobItem>()
        candidates.forEach { item ->
            val attempt = item.attempts.lastOrNull()
            val finalStatus = item.expiredStatus()
            val attempts = if (attempt == null) {
                item.attempts
            } else {
                item.attempts.dropLast(1) + attempt.copy(
                    status = finalStatus,
                    error = if (finalStatus == ScriptJobItemStatus.Cancelled) {
                        item.cancelReason ?: "script job item cancelled"
                    } else {
                        error
                    },
                    finishedAtMillis = nowMillis,
                )
            }
            val updated = item.copy(
                status = finalStatus,
                attempts = attempts,
                leaseOwner = null,
                leaseUntilMillis = null,
                updatedAtMillis = nowMillis,
            )
            val result = items.replaceOne(
                and(
                    eq("jobId", id.value),
                    eq("itemId", item.id.value),
                    eq("status", ScriptJobItemStatus.Running.name),
                    lte("leaseUntilMillis", nowMillis),
                ),
                updated.toDocument(),
            )
            if (result.modifiedCount == 1L) {
                expired += updated
            }
        }
        if (expired.isNotEmpty()) {
            refreshJob(id, nowMillis)
        }
        return expired
    }

    override suspend fun find(id: ScriptJobId): ScriptJob? {
        return jobs.find(eq("_id", id.value)).firstOrNull()?.toScriptJob()
    }

    override suspend fun listJobs(query: ScriptJobQuery): ScriptJobPage {
        val filter = query.toFilter()
        val total = jobs.countDocuments(filter)
        val page = jobs.find(filter)
            .sort(Sorts.descending("createdAtMillis"))
            .skip(query.offset)
            .limit(query.limit)
            .toList()
            .map { it.toScriptJob() }
        return ScriptJobPage(
            jobs = page,
            offset = query.offset,
            limit = query.limit,
            total = total,
        )
    }

    override suspend fun listRecoverableJobs(limit: Int): List<ScriptJob> {
        require(limit > 0) { "script job recoverable job limit must be positive" }
        return jobs.find(inFilter("status", listOf(ScriptJobStatus.Pending.name, ScriptJobStatus.Running.name)))
            .sort(Sorts.ascending("createdAtMillis"))
            .limit(limit)
            .toList()
            .map { it.toScriptJob() }
    }

    override suspend fun listItems(id: ScriptJobId, query: ScriptJobItemQuery): ScriptJobItemPage {
        val status = query.status
        val filter = if (status == null) {
            eq("jobId", id.value)
        } else {
            and(eq("jobId", id.value), eq("status", status.name))
        }
        val total = items.countDocuments(filter)
        val page = items.find(filter)
            .sort(Sorts.ascending("itemId"))
            .skip(query.offset)
            .limit(query.limit)
            .toList()
            .map { it.toScriptJobItem() }
        return ScriptJobItemPage(
            items = page,
            offset = query.offset,
            limit = query.limit,
            total = total,
        )
    }

    override suspend fun findItem(id: ScriptJobId, itemId: ScriptJobItemId): ScriptJobItem? {
        return items.find(and(eq("jobId", id.value), eq("itemId", itemId.value)))
            .firstOrNull()
            ?.toScriptJobItem()
    }

    override suspend fun cancelJob(id: ScriptJobId, cancellation: ScriptJobCancellation): ScriptJob? {
        find(id) ?: return null
        val jobItems = items.find(eq("jobId", id.value)).toList().map { it.toScriptJobItem() }
        var changed = false
        jobItems.forEach { item ->
            changed = cancelItemWithoutRefresh(item, cancellation) || changed
        }
        if (changed) {
            refreshJob(id, System.currentTimeMillis())
        }
        return find(id)
    }

    override suspend fun cancelItem(
        id: ScriptJobId,
        itemId: ScriptJobItemId,
        cancellation: ScriptJobCancellation,
    ): ScriptJobItem? {
        val item = findItem(id, itemId) ?: return null
        val changed = cancelItemWithoutRefresh(item, cancellation)
        if (changed) {
            refreshJob(id, System.currentTimeMillis())
        }
        return findItem(id, itemId)
    }

    private suspend fun replaceItem(item: ScriptJobItem) {
        items.replaceOne(
            and(eq("jobId", item.jobId.value), eq("itemId", item.id.value)),
            item.toDocument(),
        )
    }

    private suspend fun refreshJob(jobId: ScriptJobId, now: Long) {
        val job = requireNotNull(find(jobId)) { "script job $jobId not found" }
        val completed = countItems(jobId, ScriptJobItemStatus.Completed).toInt()
        val failed = countItems(jobId, ScriptJobItemStatus.Failed).toInt()
        val cancelled = countItems(jobId, ScriptJobItemStatus.Cancelled).toInt()
        val running = countItems(jobId, ScriptJobItemStatus.Running) > 0
        val pending = countItems(jobId, ScriptJobItemStatus.Pending) > 0
        val status = when {
            running || pending -> ScriptJobStatus.Running
            cancelled > 0 && failed == 0 -> ScriptJobStatus.Cancelled
            failed == 0 -> ScriptJobStatus.Completed
            completed == 0 -> ScriptJobStatus.Failed
            else -> ScriptJobStatus.PartialFailed
        }
        jobs.replaceOne(
            eq("_id", jobId.value),
            job.copy(
                status = status,
                completedItems = completed,
                failedItems = failed,
                cancelledItems = cancelled,
                updatedAtMillis = now,
            ).toDocument(),
        )
    }

    private suspend fun countItems(jobId: ScriptJobId, status: ScriptJobItemStatus): Long {
        return items.countDocuments(and(eq("jobId", jobId.value), eq("status", status.name)))
    }

    private suspend fun cancelItemWithoutRefresh(
        item: ScriptJobItem,
        cancellation: ScriptJobCancellation,
    ): Boolean {
        val now = System.currentTimeMillis()
        val updated = item.cancel(cancellation, now)
        if (updated == item) {
            return false
        }
        val result = items.replaceOne(
            and(
                eq("jobId", item.jobId.value),
                eq("itemId", item.id.value),
                eq("status", item.status.name),
            ),
            updated.toDocument(),
        )
        return result.modifiedCount == 1L
    }
}

private fun ScriptJobQuery.toFilter(): Bson {
    val filters = mutableListOf<Bson>()
    status?.let { filters += eq("status", it.name) }
    requester?.let { filters += eq("command.metadata.requester", it) }
    return if (filters.isEmpty()) Document() else and(filters)
}

private fun leaseAvailable(nowMillis: Long): Bson {
    return or(eq("leaseUntilMillis", null), lte("leaseUntilMillis", nowMillis))
}

private fun ScriptJobItem.cancel(cancellation: ScriptJobCancellation, now: Long): ScriptJobItem {
    return when (status) {
        ScriptJobItemStatus.Pending -> copy(
            status = ScriptJobItemStatus.Cancelled,
            leaseOwner = null,
            leaseUntilMillis = null,
            cancelRequestedBy = cancellation.requestedBy,
            cancelReason = cancellation.reason,
            cancelRequestedAtMillis = now,
            updatedAtMillis = now,
        )

        ScriptJobItemStatus.Running -> copy(
            cancelRequestedBy = cancellation.requestedBy,
            cancelReason = cancellation.reason,
            cancelRequestedAtMillis = cancelRequestedAtMillis ?: now,
            updatedAtMillis = now,
        )

        ScriptJobItemStatus.Completed,
        ScriptJobItemStatus.Failed,
        ScriptJobItemStatus.Cancelled,
        -> this
    }
}

private fun ScriptJobItem.finalStatus(status: ScriptJobItemStatus): ScriptJobItemStatus {
    return if (cancelRequestedAtMillis == null) status else ScriptJobItemStatus.Cancelled
}

private fun ScriptJobItem.expiredStatus(): ScriptJobItemStatus {
    return if (cancelRequestedAtMillis == null) ScriptJobItemStatus.Failed else ScriptJobItemStatus.Cancelled
}

private fun ScriptJob.toDocument(): Document {
    return Document("_id", id.value)
        .append("command", command.toDocument())
        .append("status", status.name)
        .append("attempt", attempt)
        .append("totalItems", totalItems)
        .append("completedItems", completedItems)
        .append("failedItems", failedItems)
        .append("cancelledItems", cancelledItems)
        .append("createdAtMillis", createdAtMillis)
        .append("updatedAtMillis", updatedAtMillis)
}

private fun Document.toScriptJob(): ScriptJob {
    return ScriptJob(
        id = ScriptJobId(requiredString("_id")),
        command = requiredDocument("command").toScriptExecutionCommand(),
        status = ScriptJobStatus.valueOf(requiredString("status")),
        attempt = number("attempt").toInt(),
        totalItems = number("totalItems").toInt(),
        completedItems = number("completedItems").toInt(),
        failedItems = number("failedItems").toInt(),
        cancelledItems = number("cancelledItems").toInt(),
        createdAtMillis = number("createdAtMillis"),
        updatedAtMillis = number("updatedAtMillis"),
    )
}

private fun ScriptJobItem.toDocument(): Document {
    return Document("_id", "${jobId.value}:${id.value}")
        .append("jobId", jobId.value)
        .append("itemId", id.value)
        .append("target", target.toDocument())
        .append("status", status.name)
        .append("results", results.map { it.toDocument() })
        .append("attempts", attempts.map { it.toDocument() })
        .append("leaseOwner", leaseOwner)
        .append("leaseUntilMillis", leaseUntilMillis)
        .append("cancelRequestedBy", cancelRequestedBy)
        .append("cancelReason", cancelReason)
        .append("cancelRequestedAtMillis", cancelRequestedAtMillis)
        .append("createdAtMillis", createdAtMillis)
        .append("updatedAtMillis", updatedAtMillis)
}

private fun Document.toScriptJobItem(): ScriptJobItem {
    return ScriptJobItem(
        id = ScriptJobItemId(requiredString("itemId")),
        jobId = ScriptJobId(requiredString("jobId")),
        target = requiredDocument("target").toScriptTarget(),
        status = ScriptJobItemStatus.valueOf(requiredString("status")),
        results = documents("results").map { it.toScriptExecutionResult() },
        attempts = documents("attempts").map { it.toScriptJobItemAttempt() },
        leaseOwner = nullableString("leaseOwner"),
        leaseUntilMillis = nullableNumber("leaseUntilMillis"),
        cancelRequestedBy = nullableString("cancelRequestedBy"),
        cancelReason = nullableString("cancelReason"),
        cancelRequestedAtMillis = nullableNumber("cancelRequestedAtMillis"),
        createdAtMillis = number("createdAtMillis"),
        updatedAtMillis = number("updatedAtMillis"),
    )
}

private fun ScriptJobItemAttempt.toDocument(): Document {
    return Document("attempt", attempt)
        .append("command", command.toDocument())
        .append("status", status.name)
        .append("results", results.map { it.toDocument() })
        .append("error", error)
        .append("startedAtMillis", startedAtMillis)
        .append("finishedAtMillis", finishedAtMillis)
}

private fun Document.toScriptJobItemAttempt(): ScriptJobItemAttempt {
    return ScriptJobItemAttempt(
        attempt = number("attempt").toInt(),
        command = requiredDocument("command").toScriptExecutionCommand(),
        status = ScriptJobItemStatus.valueOf(requiredString("status")),
        results = documents("results").map { it.toScriptExecutionResult() },
        error = nullableString("error"),
        startedAtMillis = number("startedAtMillis"),
        finishedAtMillis = nullableNumber("finishedAtMillis"),
    )
}

private fun ScriptExecutionCommand.toDocument(): Document {
    return Document("executionId", executionId)
        .append("target", target.toDocument())
        .append("artifact", artifact.toDocument())
        .append("metadata", metadata.toDocument())
}

private fun Document.toScriptExecutionCommand(): ScriptExecutionCommand {
    return ScriptExecutionCommand(
        executionId = requiredString("executionId"),
        target = requiredDocument("target").toScriptTarget(),
        artifact = requiredDocument("artifact").toScriptArtifact(),
        metadata = requiredDocument("metadata").toScriptExecutionMetadata(),
    )
}

private fun ScriptTarget.toDocument(): Document {
    return when (this) {
        ScriptTarget.AllNodes -> Document("type", "all-nodes")
        is ScriptTarget.Role -> Document("type", "role").append("role", role.value)
        is ScriptTarget.Node -> Document("type", "nodes").append("addresses", addresses)
        is ScriptTarget.ActorPath -> Document("type", "actor-paths").append("paths", paths)
        is ScriptTarget.Entity -> Document("type", "entity").append("kind", kind.value).append("ids", ids)
        is ScriptTarget.Singleton -> Document("type", "singleton").append("name", name.value)
    }
}

private fun Document.toScriptTarget(): ScriptTarget {
    return when (val type = requiredString("type")) {
        "all-nodes" -> ScriptTarget.AllNodes
        "role" -> ScriptTarget.Role(RoleKey(requiredString("role")))
        "nodes" -> ScriptTarget.Node(strings("addresses"))
        "actor-paths" -> ScriptTarget.ActorPath(strings("paths"))
        "entity" -> ScriptTarget.Entity(EntityKind(requiredString("kind")), strings("ids"))
        "singleton" -> ScriptTarget.Singleton(SingletonName(requiredString("name")))
        else -> error("unsupported script target type $type")
    }
}

private fun ScriptArtifact.toDocument(): Document {
    return Document("name", name)
        .append("engine", engine)
        .append("bodyBase64", Base64.getEncoder().encodeToString(body))
        .append("extraBase64", extra?.let { Base64.getEncoder().encodeToString(it) })
        .append("checksum", checksum)
}

private fun Document.toScriptArtifact(): ScriptArtifact {
    return ScriptArtifact(
        name = requiredString("name"),
        engine = requiredString("engine"),
        body = Base64.getDecoder().decode(requiredString("bodyBase64")),
        extra = nullableString("extraBase64")?.let { Base64.getDecoder().decode(it) },
        checksum = nullableString("checksum"),
    )
}

private fun ScriptExecutionMetadata.toDocument(): Document {
    return Document("requester", requester)
        .append("reason", reason)
        .append("attributes", Document(attributes))
        .append("resources", resources.map { it.toDocument() })
}

private fun Document.toScriptExecutionMetadata(): ScriptExecutionMetadata {
    return ScriptExecutionMetadata(
        requester = nullableString("requester"),
        reason = nullableString("reason"),
        attributes = requiredDocument("attributes").entries.associate { (key, value) -> key to value.toString() },
        resources = documents("resources").map { it.toScriptResourceRef() },
    )
}

private fun ScriptResourceRef.toDocument(): Document {
    return Document("name", name)
        .append("uri", uri)
        .append("checksum", checksum)
        .append("format", format)
        .append("sizeBytes", sizeBytes)
        .append("attributes", Document(attributes))
}

private fun Document.toScriptResourceRef(): ScriptResourceRef {
    return ScriptResourceRef(
        name = requiredString("name"),
        uri = requiredString("uri"),
        checksum = nullableString("checksum"),
        format = nullableString("format"),
        sizeBytes = nullableNumber("sizeBytes"),
        attributes = requiredDocument("attributes").entries.associate { (key, value) -> key to value.toString() },
    )
}

private fun ScriptExecutionResult.toDocument(): Document {
    return Document("executionId", executionId)
        .append("success", success)
        .append("target", target)
        .append("error", error)
        .append("nodeAddress", nodeAddress)
        .append("actorPath", actorPath)
}

private fun Document.toScriptExecutionResult(): ScriptExecutionResult {
    return ScriptExecutionResult(
        executionId = requiredString("executionId"),
        success = getBoolean("success"),
        target = nullableString("target"),
        error = nullableString("error"),
        nodeAddress = nullableString("nodeAddress"),
        actorPath = nullableString("actorPath"),
    )
}

private fun Document.requiredString(key: String): String {
    return requireNotNull(getString(key)) { "Mongo script job field $key is required" }
}

private fun Document.nullableString(key: String): String? {
    return getString(key)
}

private fun Document.number(key: String): Long {
    val value = requireNotNull(get(key)) { "Mongo script job field $key is required" }
    require(value is Number) { "Mongo script job field $key must be numeric" }
    return value.toLong()
}

private fun Document.nullableNumber(key: String): Long? {
    val value = get(key) ?: return null
    require(value is Number) { "Mongo script job field $key must be numeric" }
    return value.toLong()
}

private fun Document.requiredDocument(key: String): Document {
    return requireNotNull(get(key, Document::class.java)) { "Mongo script job field $key is required" }
}

private fun Document.documents(key: String): List<Document> {
    return getList(key, Document::class.java) ?: emptyList()
}

private fun Document.strings(key: String): List<String> {
    return getList(key, String::class.java) ?: emptyList()
}
