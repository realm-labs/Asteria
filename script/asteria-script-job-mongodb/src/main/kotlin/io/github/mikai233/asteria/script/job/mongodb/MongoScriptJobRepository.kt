package io.github.mikai233.asteria.script.job.mongodb

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionMetadata
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptTarget
import io.github.mikai233.asteria.script.job.ScriptJob
import io.github.mikai233.asteria.script.job.ScriptJobId
import io.github.mikai233.asteria.script.job.ScriptJobItem
import io.github.mikai233.asteria.script.job.ScriptJobItemAttempt
import io.github.mikai233.asteria.script.job.ScriptJobItemId
import io.github.mikai233.asteria.script.job.ScriptJobItemStatus
import io.github.mikai233.asteria.script.job.ScriptJobRepository
import io.github.mikai233.asteria.script.job.ScriptJobStatus
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
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
    }

    override suspend fun create(job: ScriptJob, items: List<ScriptJobItem>) {
        require(items.isNotEmpty()) { "script job items must not be empty" }
        val storedJob = job.copy(totalItems = items.size)
        jobs.insertOne(storedJob.toDocument())
        this.items.insertMany(items.map { it.toDocument() })
    }

    override suspend fun markItemRunning(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        command: ScriptExecutionCommand,
    ) {
        require(attempt > 0) { "script job item attempt must be greater than 0" }
        val item = requireNotNull(findItem(jobId, itemId)) { "script job item $itemId not found" }
        require(attempt == item.attempts.size + 1) {
            "script job item $itemId expected attempt ${item.attempts.size + 1}, got $attempt"
        }
        val now = System.currentTimeMillis()
        val oldStatus = item.status
        val updated = item.copy(
            status = ScriptJobItemStatus.Running,
            attempts = item.attempts + ScriptJobItemAttempt(
                attempt = attempt,
                command = command,
                status = ScriptJobItemStatus.Running,
                startedAtMillis = now,
            ),
            updatedAtMillis = now,
        )
        replaceItem(updated)
        refreshJob(jobId, oldStatus, ScriptJobItemStatus.Running, now)
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
        val oldStatus = item.status
        val updatedAttempt = item.attempts.last().copy(
            status = status,
            results = results,
            error = error,
            finishedAtMillis = now,
        )
        val updated = item.copy(
            status = status,
            results = results,
            attempts = item.attempts.dropLast(1) + updatedAttempt,
            updatedAtMillis = now,
        )
        replaceItem(updated)
        refreshJob(jobId, oldStatus, status, now)
    }

    override suspend fun find(id: ScriptJobId): ScriptJob? {
        return jobs.find(eq("_id", id.value)).firstOrNull()?.toScriptJob()
    }

    override suspend fun listItems(id: ScriptJobId, status: ScriptJobItemStatus?): List<ScriptJobItem> {
        val filter = if (status == null) {
            eq("jobId", id.value)
        } else {
            and(eq("jobId", id.value), eq("status", status.name))
        }
        return items.find(filter).toList().map { it.toScriptJobItem() }
    }

    override suspend fun findItem(id: ScriptJobId, itemId: ScriptJobItemId): ScriptJobItem? {
        return items.find(and(eq("jobId", id.value), eq("itemId", itemId.value)))
            .firstOrNull()
            ?.toScriptJobItem()
    }

    private suspend fun replaceItem(item: ScriptJobItem) {
        items.replaceOne(
            and(eq("jobId", item.jobId.value), eq("itemId", item.id.value)),
            item.toDocument(),
        )
    }

    private suspend fun refreshJob(
        jobId: ScriptJobId,
        oldStatus: ScriptJobItemStatus,
        newStatus: ScriptJobItemStatus,
        now: Long,
    ) {
        val job = requireNotNull(find(jobId)) { "script job $jobId not found" }
        val completed = job.completedItems + newStatus.completedDelta() - oldStatus.completedDelta()
        val failed = job.failedItems + newStatus.failedDelta() - oldStatus.failedDelta()
        val status = when {
            completed + failed < job.totalItems -> ScriptJobStatus.Running
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
                updatedAtMillis = now,
            ).toDocument(),
        )
    }
}

private fun ScriptJob.toDocument(): Document {
    return Document("_id", id.value)
        .append("command", command.toDocument())
        .append("status", status.name)
        .append("attempt", attempt)
        .append("totalItems", totalItems)
        .append("completedItems", completedItems)
        .append("failedItems", failedItems)
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
        createdAtMillis = number("createdAtMillis"),
        updatedAtMillis = number("updatedAtMillis"),
    )
}

private fun ScriptJobItemStatus.completedDelta(): Int {
    return if (this == ScriptJobItemStatus.Completed) 1 else 0
}

private fun ScriptJobItemStatus.failedDelta(): Int {
    return if (this == ScriptJobItemStatus.Failed) 1 else 0
}

private fun ScriptJobItem.toDocument(): Document {
    return Document("_id", "${jobId.value}:${id.value}")
        .append("jobId", jobId.value)
        .append("itemId", id.value)
        .append("target", target.toDocument())
        .append("status", status.name)
        .append("results", results.map { it.toDocument() })
        .append("attempts", attempts.map { it.toDocument() })
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
}

private fun Document.toScriptExecutionMetadata(): ScriptExecutionMetadata {
    return ScriptExecutionMetadata(
        requester = nullableString("requester"),
        reason = nullableString("reason"),
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
