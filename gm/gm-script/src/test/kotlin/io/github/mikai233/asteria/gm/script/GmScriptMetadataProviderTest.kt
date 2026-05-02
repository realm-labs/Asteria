package io.github.mikai233.asteria.gm.script

import io.github.mikai233.asteria.script.ScriptEngine
import io.github.mikai233.asteria.script.ScriptEngineRegistry
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.CompiledScript
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class GmScriptMetadataProviderTest {
    @Test
    fun metadataIncludesEnginesTargetsAndTemplates() = runBlocking {
        val provider = GmScriptMetadataProvider(
            engineRegistry = ScriptEngineRegistry(listOf(FakeEngine("groovy"))),
            templateCatalog = object : GmScriptTemplateCatalog {
                override suspend fun listTemplates(): List<GmScriptTemplateDescriptor> {
                    return listOf(GmScriptTemplateDescriptor("tpl-1", "Compensation", "groovy"))
                }
            },
        )

        val metadata = provider.metadata()

        assertEquals(listOf("groovy"), metadata.engines)
        assertEquals(listOf("all-nodes", "role", "nodes", "actor-paths", "entity", "singleton"), metadata.targetTypes)
        assertEquals(listOf("tpl-1"), metadata.templates.map { it.id })
    }
}

private class FakeEngine(
    override val name: String,
) : ScriptEngine {
    override fun compile(artifact: ScriptArtifact): CompiledScript {
        return CompiledScript { null }
    }
}
