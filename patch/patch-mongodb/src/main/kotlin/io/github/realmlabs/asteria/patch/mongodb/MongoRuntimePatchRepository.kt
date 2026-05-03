package io.github.realmlabs.asteria.patch.mongodb

import com.mongodb.client.model.*
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Updates.inc
import com.mongodb.client.model.Updates.set
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory

class MongoRuntimePatchRepository(
    database: MongoDatabase,
    patchesCollectionName: String = "runtime_patches",
    countersCollectionName: String = "runtime_patch_counters",
    private val metrics: Metrics = NoopMetrics,
) : RuntimePatchRepository {
    private val logger = LoggerFactory.getLogger(MongoRuntimePatchRepository::class.java)
    private val patches: MongoCollection<Document> = database.getCollection(patchesCollectionName)
    private val counters: MongoCollection<Document> = database.getCollection(countersCollectionName)

    suspend fun ensureIndexes() {
        measured("ensure_indexes") {
            patches.createIndex(Indexes.ascending("status"))
            patches.createIndex(
                Indexes.compoundIndex(
                    Indexes.ascending("compatibility.appName"),
                    Indexes.ascending("status")
                )
            )
            patches.createIndex(
                Indexes.compoundIndex(
                    Indexes.ascending("priority"),
                    Indexes.ascending("sequence"),
                    Indexes.ascending("_id"),
                ),
            )
            counters.createIndex(Indexes.ascending("_id"), IndexOptions().unique(true))
        }
    }

    override suspend fun nextSequence(): Long {
        return measured("next_sequence") {
            val updated = counters.findOneAndUpdate(
                eq("_id", SEQUENCE_COUNTER_ID),
                inc("value", 1),
                FindOneAndUpdateOptions()
                    .upsert(true)
                    .returnDocument(ReturnDocument.AFTER),
            )
            requireNotNull(updated).requiredNumber("value").toLong()
        }
    }

    override suspend fun save(patch: RuntimePatch) {
        measured("save") {
            patches.replaceOne(
                eq("_id", patch.id.value),
                patch.toDocument(),
                ReplaceOptions().upsert(true),
            )
        }
    }

    override suspend fun find(id: PatchId): RuntimePatch? {
        return measured("find") {
            patches.find(eq("_id", id.value)).firstOrNull()?.toRuntimePatch()
        }
    }

    override suspend fun list(query: RuntimePatchQuery): List<RuntimePatch> {
        return measured("list") {
            patches.find(query.toFilter())
                .sort(Sorts.ascending("priority", "sequence", "_id"))
                .toList()
                .map { it.toRuntimePatch() }
        }
    }

    override suspend fun updateStatus(id: PatchId, status: PatchStatus): RuntimePatch? {
        return measured("update_status") {
            patches.findOneAndUpdate(
                eq("_id", id.value),
                set("status", status.name),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )?.toRuntimePatch()
        }
    }

    private suspend fun <T> measured(operation: String, block: suspend () -> T): T {
        val tags = MetricTags.of("operation" to operation)
        val startedAt = System.nanoTime()
        metrics.counter("asteria.patch.mongodb.repository.operation.total", tags).increment()
        try {
            return block()
        } catch (error: Throwable) {
            metrics.counter("asteria.patch.mongodb.repository.operation.failed.total", tags).increment()
            logger.error("Mongo patch repository operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.patch.mongodb.repository.operation.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun RuntimePatchQuery.toFilter(): Bson {
        val filters = buildList {
            status?.let { add(eq("status", it.name)) }
            appName?.let { add(eq("compatibility.appName", it)) }
            version?.let { add(eq("compatibility.versions", it)) }
        }
        return when (filters.size) {
            0 -> Document()
            1 -> filters.single()
            else -> and(filters)
        }
    }

    private fun RuntimePatch.toDocument(): Document {
        return Document("_id", id.value)
            .append("name", name)
            .append("artifact", artifact.toDocument())
            .append("compatibility", compatibility.toDocument())
            .append("target", target.toDocument())
            .append("priority", priority)
            .append("sequence", sequence)
            .append("status", status.name)
    }

    private fun PatchArtifact.toDocument(): Document {
        return Document("name", name)
            .append("checksum", checksum)
            .append("version", version)
    }

    private fun PatchCompatibility.toDocument(): Document {
        return Document("appName", appName)
            .append("versions", versions.toList())
    }

    private fun PatchTarget.toDocument(): Document {
        return when (this) {
            PatchTarget.AllNodes -> Document("type", "all-nodes")
            is PatchTarget.Roles -> Document("type", "roles")
                .append("roles", roles.map { it.value })

            is PatchTarget.Nodes -> Document("type", "nodes")
                .append("addresses", addresses.toList())
        }
    }

    private fun Document.toRuntimePatch(): RuntimePatch {
        return RuntimePatch(
            id = PatchId(requiredString("_id")),
            name = requiredString("name"),
            artifact = requiredDocument("artifact").toPatchArtifact(),
            compatibility = requiredDocument("compatibility").toPatchCompatibility(),
            target = requiredDocument("target").toPatchTarget(),
            priority = requiredNumber("priority").toInt(),
            sequence = requiredNumber("sequence").toLong(),
            status = PatchStatus.valueOf(requiredString("status")),
        )
    }

    private fun Document.toPatchArtifact(): PatchArtifact {
        return PatchArtifact(
            name = requiredString("name"),
            checksum = requiredString("checksum"),
            version = getString("version"),
        )
    }

    private fun Document.toPatchCompatibility(): PatchCompatibility {
        return PatchCompatibility(
            appName = requiredString("appName"),
            versions = requiredStringList("versions").toSet(),
        )
    }

    private fun Document.toPatchTarget(): PatchTarget {
        return when (val type = requiredString("type")) {
            "all-nodes" -> PatchTarget.AllNodes
            "roles" -> PatchTarget.Roles(requiredStringList("roles").mapTo(linkedSetOf(), ::RoleKey))
            "nodes" -> PatchTarget.Nodes(requiredStringList("addresses").toSet())
            else -> error("unknown patch target type $type")
        }
    }

    private fun Document.requiredString(key: String): String {
        return requireNotNull(getString(key)) { "missing document string field $key" }
    }

    private fun Document.requiredNumber(key: String): Number {
        return requireNotNull(get(key) as? Number) { "missing document number field $key" }
    }

    private fun Document.requiredDocument(key: String): Document {
        return requireNotNull(get(key) as? Document) { "missing document field $key" }
    }

    private fun Document.requiredStringList(key: String): List<String> {
        val value = requireNotNull(get(key) as? List<*>) { "missing document list field $key" }
        return value.map { element ->
            requireNotNull(element as? String) { "document list field $key contains non-string value" }
        }
    }

    private companion object {
        const val SEQUENCE_COUNTER_ID: String = "runtime_patch_sequence"
    }
}
