package io.github.mikai233.asteria.script

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * External resource referenced by a script execution.
 *
 * A resource reference is intentionally small enough to travel inside actor messages. Large inputs such as compensation
 * tables should live in object storage, shared storage, or a pre-arranged local path and be referenced here by URI.
 */
data class ScriptResourceRef(
    val name: String,
    val uri: String,
    val checksum: String? = null,
    val format: String? = null,
    val sizeBytes: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "script resource name must not be blank" }
        require(uri.isNotBlank()) { "script resource uri must not be blank" }
        checksum?.let { require(it.isNotBlank()) { "script resource checksum must not be blank" } }
        format?.let { require(it.isNotBlank()) { "script resource format must not be blank" } }
        sizeBytes?.let { require(it >= 0) { "script resource size must not be negative" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "script resource attribute key must not be blank" }
            require(value.isNotBlank()) { "script resource attribute value must not be blank" }
        }
    }
}

/**
 * Resource resolved on the current node.
 */
data class ScriptResolvedResource(
    val ref: ScriptResourceRef,
    val localPath: Path? = null,
    val uri: URI = URI.create(ref.uri),
) {
    init {
        localPath?.let { require(it.exists()) { "script resource local path does not exist: $it" } }
    }
}

/**
 * Resolves script resource references into data accessible from the current node.
 */
fun interface ScriptResourceResolver {
    suspend fun resolve(ref: ScriptResourceRef): ScriptResolvedResource
}

/**
 * Resolver for `file:` and plain local path resource URIs.
 */
object LocalScriptResourceResolver : ScriptResourceResolver {
    override suspend fun resolve(ref: ScriptResourceRef): ScriptResolvedResource {
        val uri = runCatching { URI.create(ref.uri) }.getOrNull()
        val path = when (uri?.scheme) {
            null -> Path(ref.uri)
            "file" -> Paths.get(uri)
            else -> error("unsupported local script resource uri scheme ${uri.scheme}")
        }
        return ScriptResolvedResource(ref = ref, localPath = path, uri = path.toUri())
    }
}

/**
 * Convenience access to resources attached to a script execution.
 */
class ScriptResources(
    private val refs: List<ScriptResourceRef>,
    private val resolver: ScriptResourceResolver?,
) {
    fun all(): List<ScriptResourceRef> = refs

    fun ref(name: String): ScriptResourceRef {
        return refs.firstOrNull { it.name == name } ?: error("script resource $name not found")
    }

    suspend fun resolve(name: String): ScriptResolvedResource {
        val resource = ref(name)
        val resourceResolver = resolver ?: LocalScriptResourceResolver
        return resourceResolver.resolve(resource)
    }
}
