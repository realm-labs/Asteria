package io.github.mikai233.asteria.gm.cluster

/**
 * Diagnostic actor query submitted from GM tools.
 *
 * `target` is intentionally runtime-specific. A Pekko adapter may interpret it as an actor path, shard entity id, or
 * singleton name, while another runtime can use its own addressing format.
 */
data class GmActorQuery(
    val target: String,
    val payload: String? = null,
    val timeoutMillis: Long = 3_000,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(target.isNotBlank()) { "GM actor query target must not be blank" }
        require(timeoutMillis > 0) { "GM actor query timeout must be positive" }
        payload?.let { require(it.isNotBlank()) { "GM actor query payload must not be blank" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM actor query attribute key must not be blank" }
            require(value.isNotBlank()) { "GM actor query attribute value must not be blank" }
        }
    }
}

/**
 * Result returned from a diagnostic actor query.
 */
data class GmActorQueryResult(
    val target: String,
    val success: Boolean,
    val payload: String? = null,
    val error: String? = null,
) {
    init {
        require(target.isNotBlank()) { "GM actor query result target must not be blank" }
        payload?.let { require(it.isNotBlank()) { "GM actor query result payload must not be blank" } }
        error?.let { require(it.isNotBlank()) { "GM actor query result error must not be blank" } }
    }
}

/**
 * Runtime adapter for actor diagnostics.
 *
 * Implementations should keep this operation read-only unless the application deliberately exposes a controlled
 * mutation command behind a separate high-risk permission.
 */
fun interface GmActorQueryService {
    suspend fun query(query: GmActorQuery): GmActorQueryResult
}
