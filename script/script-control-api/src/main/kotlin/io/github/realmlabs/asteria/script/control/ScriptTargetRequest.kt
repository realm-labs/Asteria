package io.github.realmlabs.asteria.script.control

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.SingletonName
import io.github.realmlabs.asteria.script.ScriptTarget

/**
 * Wire representation of [ScriptTarget] used by HTTP and control-plane APIs.
 *
 * [type] is one of `all-nodes`, `role`, `nodes`, `actor-paths`, `entity`, or `singleton`. Only the fields required by
 * that target type are read; missing or blank required values fail conversion.
 */
data class ScriptTargetRequest(
    val type: String,
    val role: String? = null,
    val addresses: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val kind: String? = null,
    val ids: List<String> = emptyList(),
    val name: String? = null,
)

/**
 * Converts a control-plane target request to the runtime routing model.
 */
fun ScriptTargetRequest.toScriptTarget(): ScriptTarget {
    return when (type) {
        "all-nodes" -> ScriptTarget.AllNodes
        "role" -> ScriptTarget.Role(RoleKey(requireValue(role, "role")))
        "nodes" -> ScriptTarget.Node(requireValues(addresses, "addresses"))
        "actor-paths" -> ScriptTarget.ActorPath(requireValues(paths, "paths"))
        "entity" -> ScriptTarget.Entity(
            kind = EntityKind(requireValue(kind, "kind")),
            ids = requireValues(ids, "ids"),
        )

        "singleton" -> ScriptTarget.Singleton(SingletonName(requireValue(name, "name")))
        else -> error("unsupported script target type $type")
    }
}

private fun requireValue(value: String?, name: String): String {
    return value?.takeIf { it.isNotBlank() } ?: error("script target $name is required")
}

private fun requireValues(values: List<String>, name: String): List<String> {
    require(values.isNotEmpty()) { "script target $name must not be empty" }
    require(values.all { it.isNotBlank() }) { "script target $name must not contain blank values" }
    return values
}
