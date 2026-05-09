package io.github.realmlabs.asteria.script.control

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.SingletonName
import io.github.realmlabs.asteria.script.ScriptTarget

data class ScriptTargetRequest(
    val type: String,
    val role: String? = null,
    val addresses: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val kind: String? = null,
    val ids: List<String> = emptyList(),
    val name: String? = null,
)

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
