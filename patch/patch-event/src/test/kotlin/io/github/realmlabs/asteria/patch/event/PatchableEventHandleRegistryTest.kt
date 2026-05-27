package io.github.realmlabs.asteria.patch.event

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import io.github.realmlabs.asteria.event.EventDispatcher
import io.github.realmlabs.asteria.event.EventPublisher
import io.github.realmlabs.asteria.event.EventTopic
import io.github.realmlabs.asteria.event.EventTopicCatalog
import io.github.realmlabs.asteria.event.GameEvent
import io.github.realmlabs.asteria.event.eventHandleKey
import io.github.realmlabs.asteria.event.eventTopics
import io.github.realmlabs.asteria.message.DefaultHandlerContext
import io.github.realmlabs.asteria.patch.PatchApplyResult
import io.github.realmlabs.asteria.patch.PatchId
import io.github.realmlabs.asteria.patch.PatchRuntime
import io.github.realmlabs.asteria.patch.RuntimePatch
import io.github.realmlabs.asteria.patch.RuntimePatchInstallContext
import io.github.realmlabs.asteria.patch.RuntimePatchPlugin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PatchableEventHandleRegistryTest {
    @Test
    fun `dispatcher uses current patchable event handle`() = runBlocking {
        val records = mutableListOf<String>()
        val handleKey = eventHandleKey(GeneratedPlayerLevelChangedHandler::class)
        val registry = PatchableEventHandleRegistry<DefaultHandlerContext>()
        registry.register(PlayerLevelChanged::class, key = handleKey) { _, event, _ ->
            records += "base:${event.newLevel}"
        }
        val dispatcher = EventDispatcher(registry)
        val runtime = PatchRuntime(TestRuntime)

        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))
        assertIs<PatchApplyResult.Applied>(
            runtime.apply(
                patch("level-handler-fix"),
                plugin {
                    eventHandlers.replaceEventType(
                        registry,
                        PlayerLevelChanged::class,
                        key = handleKey
                    ) { _, event, _ ->
                        records += "patched:${event.newLevel}"
                    }
                },
            ),
        )
        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 2, newLevel = 3))

        assertEquals(listOf("base:2", "patched:3"), records)
    }

    @Test
    fun `removing patch restores previous event handler layer`() = runBlocking {
        val records = mutableListOf<String>()
        val handleKey = eventHandleKey(GeneratedProgressionTopicHandler::class, PlayerEvents.Progression.topic)
        val registry = PatchableEventHandleRegistry<DefaultHandlerContext>()
        registry.registerTopic(PlayerEvents.Progression.topic, key = handleKey) { _, _, _ ->
            records += "base"
        }
        val dispatcher = EventDispatcher(registry)
        val runtime = PatchRuntime(TestRuntime)
        val first = patch("first", revision = 1)
        val second = patch("second", revision = 2)

        assertIs<PatchApplyResult.Applied>(
            runtime.apply(first, plugin {
                eventHandlers.replaceTopic(registry, PlayerEvents.Progression.topic, key = handleKey) { _, _, _ ->
                    records += "first"
                }
            }),
        )
        assertIs<PatchApplyResult.Applied>(
            runtime.apply(second, plugin {
                eventHandlers.replaceTopic(registry, PlayerEvents.Progression.topic, key = handleKey) { _, _, _ ->
                    records += "second"
                }
            }),
        )
        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))

        runtime.remove(second.id)
        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 2, newLevel = 3))

        runtime.remove(first.id)
        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 3, newLevel = 4))

        assertEquals(listOf("second", "first", "base"), records)
    }

    private fun context(): DefaultHandlerContext {
        return DefaultHandlerContext(TestRuntime)
    }

    private fun patch(
        id: String,
        revision: Long = 1,
    ): RuntimePatch {
        return RuntimePatch(PatchId(id), revision)
    }

    private fun plugin(block: RuntimePatchInstallContext.() -> Unit): RuntimePatchPlugin {
        return object : RuntimePatchPlugin {
            override suspend fun install(context: RuntimePatchInstallContext) {
                context.block()
            }
        }
    }

    private object PlayerEvents : EventTopicCatalog("player") {
        object Progression : EventTopicCatalog(PlayerEvents, "progression")
    }

    private data class PlayerLevelChanged(
        val oldLevel: Int,
        val newLevel: Int,
    ) : GameEvent {
        override val topics: Set<EventTopic> = eventTopics(PlayerEvents.Progression.topic)
    }

    private class GeneratedPlayerLevelChangedHandler {
        fun handle(
            context: DefaultHandlerContext,
            event: PlayerLevelChanged,
            publisher: EventPublisher<DefaultHandlerContext>,
        ) {
            context.runtime.name
            event.newLevel
            publisher.toString()
        }
    }

    private class GeneratedProgressionTopicHandler {
        fun handle(
            context: DefaultHandlerContext,
            event: GameEvent,
            publisher: EventPublisher<DefaultHandlerContext>,
        ) {
            context.runtime.name
            event.topics
            publisher.toString()
        }
    }

    private object TestRuntime : NodeRuntime {
        override val name: String = "test"
        override val roles: Set<RoleKey> = emptySet()
        override val state: NodeState = NodeState.Started
        override val services: ServiceRegistry = ServiceRegistry()
    }
}
