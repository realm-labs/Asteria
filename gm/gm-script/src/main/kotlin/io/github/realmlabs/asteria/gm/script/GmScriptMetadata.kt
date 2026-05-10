package io.github.realmlabs.asteria.gm.script

import io.github.realmlabs.asteria.cluster.pekko.EntityShardRegistry
import io.github.realmlabs.asteria.cluster.pekko.SingletonActorRegistry
import io.github.realmlabs.asteria.script.ScriptEngineRegistry
import io.github.realmlabs.asteria.script.ScriptTarget

data class GmScriptMetadata(
    val engines: List<String>,
    val targetTypes: List<String>,
    val entityKinds: List<String> = emptyList(),
    val singletons: List<String> = emptyList(),
    val templates: List<GmScriptTemplateDescriptor> = emptyList(),
)

data class GmScriptTemplateDescriptor(
    val id: String,
    val name: String,
    val engine: String,
) {
    init {
        require(id.isNotBlank()) { "GM script template id must not be blank" }
        require(name.isNotBlank()) { "GM script template name must not be blank" }
        require(engine.isNotBlank()) { "GM script template engine must not be blank" }
    }
}

interface GmScriptTemplateCatalog {
    suspend fun listTemplates(): List<GmScriptTemplateDescriptor> = emptyList()
}

class GmScriptMetadataProvider(
    private val engineRegistry: ScriptEngineRegistry? = null,
    private val routeRegistry: GmScriptRouteRegistryView = GmScriptRouteRegistryView(),
    private val templateCatalog: GmScriptTemplateCatalog? = null,
) {
    suspend fun metadata(): GmScriptMetadata {
        return GmScriptMetadata(
            engines = engineRegistry?.all()?.map { it.name }?.sorted().orEmpty(),
            targetTypes = listOf("all-nodes", "role", "nodes", "actor-paths", "entity", "singleton"),
            entityKinds = routeRegistry.entityKinds(),
            singletons = routeRegistry.singletons(),
            templates = templateCatalog?.listTemplates().orEmpty(),
        )
    }
}

class GmScriptRouteRegistryView(
    private val entityShards: EntityShardRegistry? = null,
    private val singletonActors: SingletonActorRegistry? = null,
) {
    fun entityKinds(): List<String> {
        return entityShards
            ?.all()
            ?.keys
            ?.map { it.value }
            ?.sorted()
            .orEmpty()
    }

    fun singletons(): List<String> {
        return singletonActors
            ?.all()
            ?.keys
            ?.map { it.value }
            ?.sorted()
            .orEmpty()
    }

    fun validate(target: ScriptTarget): List<String> {
        return when (target) {
            ScriptTarget.AllNodes,
            is ScriptTarget.ActorPath,
            is ScriptTarget.Node,
            is ScriptTarget.Role,
                -> emptyList()

            is ScriptTarget.Entity -> {
                if (entityShards?.find(target.kind) == null) {
                    listOf("script entity kind ${target.kind.value} is not routable from this GM node")
                } else {
                    emptyList()
                }
            }

            is ScriptTarget.Singleton -> {
                if (singletonActors?.find(target.name) == null) {
                    listOf("script singleton ${target.name.value} is not routable from this GM node")
                } else {
                    emptyList()
                }
            }
        }
    }
}
