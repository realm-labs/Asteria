package io.github.realmlabs.asteria.patch.event

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import io.github.realmlabs.asteria.event.EventDispatcher
import io.github.realmlabs.asteria.event.EventHandle
import io.github.realmlabs.asteria.event.EventPublisher
import io.github.realmlabs.asteria.event.EventTopic
import io.github.realmlabs.asteria.event.EventTopicCatalog
import io.github.realmlabs.asteria.event.GameEvent
import io.github.realmlabs.asteria.event.eventHandleKey
import io.github.realmlabs.asteria.event.eventTopics
import io.github.realmlabs.asteria.message.DefaultHandlerContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HotswapEventHandleRegistryTest {
    @Test
    fun `dispatcher uses directly replaced event handler`() {
        val records = mutableListOf<String>()
        val key = eventHandleKey(GeneratedPlayerLevelChangedHandler::class)
        val registry = HotswapEventHandleRegistry<DefaultHandlerContext>()
        registry.register(PlayerLevelChanged::class, key = key) { _, event, _ ->
            records += "base:${event.newLevel}"
        }
        val dispatcher = EventDispatcher(registry)

        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))
        val previous = registry.replaceEventType(PlayerLevelChanged::class, key = key) { _, event, _ ->
            records += "hotswap:${event.newLevel}"
        }
        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 2, newLevel = 3))

        assertNotNull(previous)
        assertEquals(listOf("base:2", "hotswap:3"), records)
    }

    @Test
    fun `dispatcher uses directly replaced topic handler`() {
        val records = mutableListOf<String>()
        val key = eventHandleKey(GeneratedProgressionTopicHandler::class, PlayerEvents.Progression.topic)
        val registry = HotswapEventHandleRegistry<DefaultHandlerContext>()
        registry.registerTopic(PlayerEvents.Progression.topic, key = key) { _, _, _ ->
            records += "base"
        }
        val dispatcher = EventDispatcher(registry)

        registry.replaceTopic(PlayerEvents.Progression.topic, key = key) { _, _, _ ->
            records += "hotswap"
        }
        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))

        assertEquals(listOf("hotswap"), records)
    }

    @Test
    fun `replace rejects missing event slot`() {
        val registry = HotswapEventHandleRegistry<DefaultHandlerContext>()

        assertFailsWith<IllegalStateException> {
            registry.replace(EventHandle.forEventType(PlayerLevelChanged::class) { _, _, _ -> })
        }
    }

    @Test
    fun `remove deletes event slot`() {
        val key = eventHandleKey(GeneratedPlayerLevelChangedHandler::class)
        val registry = HotswapEventHandleRegistry<DefaultHandlerContext>()
        registry.register(PlayerLevelChanged::class, key = key) { _, _, _ -> }

        assertNotNull(registry.remove(key))
        assertNull(registry.snapshot()[key])
        assertEquals(emptyList(), registry.handlersFor(PlayerLevelChanged::class))
    }

    private fun context(): DefaultHandlerContext {
        return DefaultHandlerContext(TestRuntime)
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
