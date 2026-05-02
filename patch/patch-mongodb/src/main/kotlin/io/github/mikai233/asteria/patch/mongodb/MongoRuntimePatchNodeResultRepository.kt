package io.github.mikai233.asteria.patch.mongodb

import com.mongodb.client.model.*
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Updates.inc
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.patch.*
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Instant

class MongoRuntimePatchNodeResultRepository(
    database: MongoDatabase,
    resultsCollectionName: String = "runtime_patch_node_results",
    countersCollectionName: String = "runtime_patch_node_result_counters",
) : RuntimePatchNodeResultRepository {
    private val results: MongoCollection<Document> = database.getCollection(resultsCollectionName)
    private val counters: MongoCollection<Document> = database.getCollection(countersCollectionName)

    suspend fun ensureIndexes() {
        results.createIndex(Indexes.compoundIndex(Indexes.ascending("patchId"), Indexes.ascending("address")))
        results.createIndex(Indexes.ascending("status"))
        results.createIndex(Indexes.descending("updatedAt"))
        counters.createIndex(Indexes.ascending("_id"), IndexOptions().unique(true))
    }

    override suspend fun nextAttempt(
        patchId: PatchId,
        address: String,
    ): Int {
        val updated = counters.findOneAndUpdate(
            eq("_id", counterId(patchId, address)),
            inc("value", 1),
            FindOneAndUpdateOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.AFTER),
        )
        return requireNotNull(updated).requiredNumber("value").toInt()
    }

    override suspend fun save(result: RuntimePatchNodeResult) {
        results.replaceOne(
            eq("_id", result.documentId()),
            result.toDocument(),
            ReplaceOptions().upsert(true),
        )
    }

    override suspend fun list(query: RuntimePatchNodeResultQuery): List<RuntimePatchNodeResult> {
        return results.find(query.toFilter())
            .sort(Sorts.ascending("patchId", "address", "attempt"))
            .toList()
            .map { it.toRuntimePatchNodeResult() }
    }

    private fun RuntimePatchNodeResultQuery.toFilter(): Bson {
        val filters = buildList {
            patchId?.let { add(eq("patchId", it.value)) }
            address?.let { add(eq("address", it)) }
            status?.let { add(eq("status", it.name)) }
        }
        return when (filters.size) {
            0 -> Document()
            1 -> filters.single()
            else -> and(filters)
        }
    }

    private fun RuntimePatchNodeResult.toDocument(): Document {
        return Document("_id", documentId())
            .append("patchId", patchId.value)
            .append("nodeId", nodeId)
            .append("address", address)
            .append("appName", appName)
            .append("version", version)
            .append("roles", roles.map { it.value })
            .append("status", status.name)
            .append("attempt", attempt)
            .append("operationCount", operationCount)
            .append("message", message)
            .append("updatedAt", updatedAt.toEpochMilli())
    }

    private fun Document.toRuntimePatchNodeResult(): RuntimePatchNodeResult {
        return RuntimePatchNodeResult(
            patchId = PatchId(requiredString("patchId")),
            nodeId = getString("nodeId"),
            address = requiredString("address"),
            appName = requiredString("appName"),
            version = requiredString("version"),
            roles = requiredStringList("roles").mapTo(linkedSetOf(), ::RoleKey),
            status = RuntimePatchNodeStatus.valueOf(requiredString("status")),
            attempt = requiredNumber("attempt").toInt(),
            operationCount = get("operationCount")?.let { (it as Number).toInt() },
            message = getString("message"),
            updatedAt = Instant.ofEpochMilli(requiredNumber("updatedAt").toLong()),
        )
    }

    private fun RuntimePatchNodeResult.documentId(): String {
        return "${patchId.value}:$address:$attempt"
    }

    private fun counterId(
        patchId: PatchId,
        address: String,
    ): String {
        return "${patchId.value}:$address"
    }

    private fun Document.requiredString(key: String): String {
        return requireNotNull(getString(key)) { "missing document string field $key" }
    }

    private fun Document.requiredNumber(key: String): Number {
        return requireNotNull(get(key) as? Number) { "missing document number field $key" }
    }

    private fun Document.requiredStringList(key: String): List<String> {
        val value = requireNotNull(get(key) as? List<*>) { "missing document list field $key" }
        return value.map { element ->
            requireNotNull(element as? String) { "document list field $key contains non-string value" }
        }
    }
}
