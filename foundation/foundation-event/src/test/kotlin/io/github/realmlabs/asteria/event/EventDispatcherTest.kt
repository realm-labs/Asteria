package io.github.realmlabs.asteria.event

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import io.github.realmlabs.asteria.message.DefaultHandlerContext
import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EventDispatcherTest {
    @Test
    fun `topic catalog builds parent chain and registry tree`() {
        val registry = EventTopicRegistry()
        val player = EventTopicCatalog("player", registry)
        val progression = EventTopicCatalog(player, "progression")
        val level = EventTopicCatalog(progression, "level")
        val levelChanged = level.event("changed")
        val rebuilt = eventTopicPath("player.progression.level.changed")

        assertEquals("player.progression.level.changed", levelChanged.path)
        assertEquals(levelChanged, rebuilt)
        assertEquals(
            listOf("player", "player.progression", "player.progression.level", "player.progression.level.changed"),
            levelChanged.ancestorsAndSelf().map { it.path },
        )
        assertEquals(levelChanged, registry.find("player.progression.level.changed"))
        assertEquals(listOf("player.progression"), registry.childrenOf(player.topic).map { it.path })
    }

    @Test
    fun `dispatcher invokes event type handler and ancestor topic handlers`() {
        val records = mutableListOf<String>()
        val registry = eventHandlers<DefaultHandlerContext> {
            on<PlayerLevelChanged> { _, event, _ ->
                records += "type:${event.oldLevel}->${event.newLevel}"
            }
            onTopic(PlayerEvents.topic) { _, event, _ ->
                records += "player:${event::class.simpleName}"
            }
            onTopic(PlayerEvents.Progression.topic) { _, _, _ ->
                records += "progression"
            }
            onTopic(PlayerEvents.Progression.Level.topic) { _, _, _ ->
                records += "level"
            }
            onTopic(PlayerEvents.Progression.Level.changed) { _, _, _ ->
                records += "level-changed"
            }
        }
        val dispatcher = EventDispatcher(registry)

        val result = dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))

        assertEquals(
            listOf("type:1->2", "player:PlayerLevelChanged", "progression", "level", "level-changed"),
            records,
        )
        assertEquals(
            setOf(
                PlayerEvents.topic,
                PlayerEvents.Progression.topic,
                PlayerEvents.Progression.Level.topic,
                PlayerEvents.Progression.Level.changed,
            ),
            result.matchedTopics,
        )
        assertEquals(5, result.invokedHandlers.size)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `generated style handles compile and dispatch`() {
        val records = mutableListOf<String>()
        val registry = DefaultEventHandleRegistry(GeneratedPlayerEventHandles.all(records))
        val dispatcher = EventDispatcher(registry)

        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))

        assertEquals(listOf("generated-type:2", "generated-topic:player.progression.level.changed"), records)
    }

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

    @Test
    fun `dispatcher treats events without subscribers as normal`() {
        val dispatcher = EventDispatcher(eventHandlers<DefaultHandlerContext> {})

        val result = dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))

        assertTrue(result.invokedHandlers.isEmpty())
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `continue failure policy records handler errors and invokes remaining handlers`() {
        val records = mutableListOf<String>()
        val registry = eventHandlers<DefaultHandlerContext> {
            onTopic(PlayerEvents.Progression.topic) { _, _, _ ->
                error("broken")
            }
            onTopic(PlayerEvents.Progression.Level.changed) { _, _, _ ->
                records += "continued"
            }
        }
        val dispatcher = EventDispatcher(
            registry,
            EventDispatchOptions(failurePolicy = EventDispatchFailurePolicy.CONTINUE),
        )

        val result = dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))

        assertEquals(listOf("continued"), records)
        assertEquals(1, result.failures.size)
        assertEquals("broken", result.failures.single().error.message)
    }

    @Test
    fun `fail fast failure policy propagates handler error`() {
        val dispatcher = EventDispatcher(
            eventHandlers<DefaultHandlerContext> {
                onTopic(PlayerEvents.Progression.topic) { _, _, _ ->
                    error("broken")
                }
                onTopic(PlayerEvents.Progression.Level.changed) { _, _, _ ->
                    error("should not run")
                }
            },
        )

        val error = assertFailsWith<IllegalStateException> {
            dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))
        }

        assertEquals("broken", error.message)
    }

    @Test
    fun `dispatcher fails fast when nested publish depth exceeds limit`() {
        val dispatcher = EventDispatcher(
            eventHandlers {
                onTopic(PlayerEvents.Progression.Level.changed) { _, _, publisher ->
                    publisher.publish(PlayerLevelChanged(oldLevel = 2, newLevel = 3))
                }
            },
            EventDispatchOptions(maxNestedDepth = 2),
        )

        val error = assertFailsWith<EventDispatchLimitExceededException> {
            dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))
        }

        assertTrue(error.message.orEmpty().startsWith("event dispatch nested depth exceeded 2:"))
    }

    @Test
    fun `dispatcher fails fast when published event count exceeds limit`() {
        lateinit var dispatcher: EventDispatcher<DefaultHandlerContext>
        var nextLevel = 2
        dispatcher = EventDispatcher(
            eventHandlers {
                onTopic(PlayerEvents.Progression.Level.changed) { _, _, publisher ->
                    nextLevel += 1
                    publisher.publish(PlayerLevelChanged(oldLevel = nextLevel - 1, newLevel = nextLevel))
                }
            },
            EventDispatchOptions(maxNestedDepth = 10, maxPublishedEvents = 3),
        )

        val error = assertFailsWith<EventDispatchLimitExceededException> {
            dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))
        }

        assertTrue(error.message.orEmpty().startsWith("event dispatch published event limit exceeded 3:"))
    }

    @Test
    fun `queued dispatcher schedules handlers without running them inline`() {
        val records = mutableListOf<String>()
        var pumpRequests = 0
        val dispatcher = QueuedEventDispatcher(
            eventHandlers<DefaultHandlerContext> {
                on<PlayerLevelChanged> { _, event, _ ->
                    records += "type:${event.newLevel}"
                }
                onTopic(PlayerEvents.Progression.Level.changed, order = 10) { _, _, _ ->
                    records += "topic"
                }
            },
            schedulePump = { pumpRequests += 1 },
        )

        val receipt = dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))

        assertEquals(2, receipt.scheduledHandlers)
        assertEquals(2, dispatcher.pendingHandlers())
        assertEquals(1, pumpRequests)
        assertTrue(records.isEmpty())

        val firstPump = dispatcher.pump(maxHandlers = 1)

        assertEquals(listOf("type:2"), records)
        assertEquals(1, firstPump.invokedHandlers.size)
        assertEquals(1, firstPump.remainingHandlers)
        assertEquals(2, pumpRequests)

        dispatcher.pump(maxHandlers = 1)

        assertEquals(listOf("type:2", "topic"), records)
        assertEquals(0, dispatcher.pendingHandlers())
    }

    @Test
    fun `queued dispatcher keeps nested events in the same budget`() {
        val dispatcher = QueuedEventDispatcher(
            eventHandlers<DefaultHandlerContext> {
                on<PlayerLevelChanged> { _, _, publisher ->
                    publisher.publish(PlayerLevelChanged(oldLevel = 2, newLevel = 3))
                }
            },
            EventDispatchOptions(maxNestedDepth = 1),
            schedulePump = {},
        )
        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))

        val error = assertFailsWith<EventDispatchLimitExceededException> {
            dispatcher.pump(maxHandlers = 1)
        }

        assertTrue(error.message.orEmpty().startsWith("event dispatch nested depth exceeded 1:"))
    }

    @Test
    fun `queued dispatcher fail fast aborts remaining handlers from the same session`() {
        val records = mutableListOf<String>()
        val dispatcher = QueuedEventDispatcher(
            eventHandlers<DefaultHandlerContext> {
                on<PlayerLevelChanged> { _, _, _ ->
                    error("broken")
                }
                onTopic(PlayerEvents.Progression.Level.changed, order = 10) { _, _, _ ->
                    records += "should-not-run"
                }
            },
            schedulePump = {},
        )
        dispatcher.publish(context(), PlayerLevelChanged(oldLevel = 1, newLevel = 2))

        val error = assertFailsWith<IllegalStateException> {
            dispatcher.pump(maxHandlers = 10)
        }

        assertEquals("broken", error.message)
        assertEquals(0, dispatcher.pendingHandlers())
        assertTrue(records.isEmpty())
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
        object Progression : EventTopicCatalog(PlayerEvents, "progression") {
            object Level : EventTopicCatalog(Progression, "level") {
                val changed = event("changed")
            }
        }
    }

    private data class PlayerLevelChanged(
        val oldLevel: Int,
        val newLevel: Int,
    ) : GameEvent {
        override val topics: Set<EventTopic> = eventTopics(PlayerEvents.Progression.Level.changed)
    }

    private object GeneratedPlayerEventHandles {
        fun all(records: MutableList<String>): List<EventHandle<DefaultHandlerContext>> {
            return listOf(
                EventHandle.forEventType(
                    PlayerLevelChanged::class,
                    order = 0,
                    key = eventHandleKey(GeneratedPlayerLevelChangedHandler::class),
                ) { context, event, publisher ->
                    GeneratedPlayerLevelChangedHandler(records).handle(context, event, publisher)
                },
                EventHandle.forTopic(
                    eventTopicPath("player.progression"),
                    order = 100,
                    key = eventHandleKey(GeneratedProgressionTopicHandler::class, "player.progression"),
                ) { context, event, publisher ->
                    GeneratedProgressionTopicHandler(records).handle(context, event, publisher)
                },
            )
        }
    }

    private class GeneratedPlayerLevelChangedHandler(
        private val records: MutableList<String>,
    ) {
        fun handle(
            context: DefaultHandlerContext,
            event: PlayerLevelChanged,
            publisher: EventPublisher<DefaultHandlerContext>,
        ) {
            records += "generated-type:${event.newLevel}"
            context.runtime.name
            publisher.toString()
        }
    }

    private class GeneratedProgressionTopicHandler(
        private val records: MutableList<String>,
    ) {
        fun handle(
            context: DefaultHandlerContext,
            event: GameEvent,
            publisher: EventPublisher<DefaultHandlerContext>,
        ) {
            records += "generated-topic:${event.topics.single()}"
            context.runtime.name
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
