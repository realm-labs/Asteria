package io.github.mikai233.asteria.gm.script

import io.github.mikai233.asteria.script.ScriptEngineRegistry

data class GmScriptMetadata(
    val engines: List<String>,
    val targetTypes: List<String>,
    val roles: List<String> = emptyList(),
    val entityKinds: List<String> = emptyList(),
    val singletons: List<String> = emptyList(),
    val nodeAddresses: List<String> = emptyList(),
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
    private val targetCatalog: GmScriptTargetCatalog? = null,
    private val templateCatalog: GmScriptTemplateCatalog? = null,
) {
    suspend fun metadata(): GmScriptMetadata {
        return GmScriptMetadata(
            engines = engineRegistry?.all()?.map { it.name }?.sorted().orEmpty(),
            targetTypes = listOf("all-nodes", "role", "nodes", "actor-paths", "entity", "singleton"),
            roles = targetCatalog?.listRoles().orEmpty(),
            entityKinds = targetCatalog?.listEntityKinds().orEmpty(),
            singletons = targetCatalog?.listSingletons().orEmpty(),
            nodeAddresses = targetCatalog?.listNodeAddresses().orEmpty(),
            templates = templateCatalog?.listTemplates().orEmpty(),
        )
    }
}
