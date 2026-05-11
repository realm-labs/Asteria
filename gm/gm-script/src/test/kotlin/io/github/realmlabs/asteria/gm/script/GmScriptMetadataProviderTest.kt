package io.github.realmlabs.asteria.gm.script

import io.github.realmlabs.asteria.cluster.pekko.EntityShardRegistry
import io.github.realmlabs.asteria.cluster.pekko.SingletonActorRegistry
import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.SingletonName
import io.github.realmlabs.asteria.script.CompiledScript
import io.github.realmlabs.asteria.script.ScriptArtifact
import io.github.realmlabs.asteria.script.ScriptEngine
import io.github.realmlabs.asteria.script.ScriptEngineRegistry
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class GmScriptMetadataProviderTest {
    @Test
    fun metadataIncludesEnginesRoutableTargetsAndTemplates() = runBlocking {
        val system = ActorSystem.create("gm-script-metadata-test")
        val entityShards = EntityShardRegistry().apply {
            register(EntityKind("player"), system.deadLetters())
        }
        val singletonActors = SingletonActorRegistry().apply {
            register(SingletonName("world"), system.deadLetters())
        }
        val provider = GmScriptMetadataProvider(
            engineRegistry = ScriptEngineRegistry(listOf(FakeEngine("groovy"))),
            routeRegistry = GmScriptRouteRegistryView(entityShards, singletonActors),
            templateCatalog = object : GmScriptTemplateCatalog {
                override suspend fun listTemplates(): List<GmScriptTemplateDescriptor> {
                    return listOf(GmScriptTemplateDescriptor("tpl-1", "Compensation", "groovy"))
                }
            },
        )

        try {
            val metadata = provider.metadata()

            assertEquals(listOf("groovy"), metadata.engines)
            assertEquals(
                listOf("all-nodes", "role", "nodes", "actor-paths", "entity", "singleton"),
                metadata.targetTypes
            )
            assertEquals(listOf("player"), metadata.entityKinds)
            assertEquals(listOf("world"), metadata.singletons)
            assertEquals(listOf("tpl-1"), metadata.templates.map { it.id })
        } finally {
            system.terminate()
        }
    }
}

private class FakeEngine(
    override val name: String,
) : ScriptEngine {
    override fun compile(artifact: ScriptArtifact): CompiledScript {
        return CompiledScript { }
    }
}
