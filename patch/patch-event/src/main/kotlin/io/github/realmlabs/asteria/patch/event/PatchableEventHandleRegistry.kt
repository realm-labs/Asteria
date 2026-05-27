package io.github.realmlabs.asteria.patch.event

import io.github.realmlabs.asteria.event.EventHandle
import io.github.realmlabs.asteria.event.EventHandleKey
import io.github.realmlabs.asteria.event.EventHandleRegistry
import io.github.realmlabs.asteria.event.EventHandler
import io.github.realmlabs.asteria.event.EventTopic
import io.github.realmlabs.asteria.event.GameEvent
import io.github.realmlabs.asteria.message.HandlerContext
import io.github.realmlabs.asteria.patch.PatchId
import io.github.realmlabs.asteria.patch.PatchOrder
import io.github.realmlabs.asteria.patch.PatchRegistryMutationScope
import io.github.realmlabs.asteria.patch.PatchSlotRegistry
import io.github.realmlabs.asteria.patch.PatchableIndexedRegistry
import io.github.realmlabs.asteria.patch.RuntimePatchInstallContext
import kotlin.reflect.KClass

/**
 * Event-handle registry whose event slots can be replaced by runtime patch layers.
 */
class PatchableEventHandleRegistry<C : HandlerContext>(
    handles: Iterable<EventHandle<C>> = emptyList(),
) : EventHandleRegistry<C>, PatchSlotRegistry<EventHandleKey, EventHandle<C>> {
    private val registry = PatchableIndexedRegistry<EventHandleKey, EventHandle<C>, EventHandleIndex<C>>(
        entries = handles.associateByKey(),
        indexFactory = { active -> EventHandleIndex(active.values) },
    )

    override fun handlersFor(eventType: KClass<out GameEvent>): List<EventHandle<C>> {
        return registry.index().byEventType[eventType].orEmpty()
    }

    override fun handlersFor(topic: EventTopic): List<EventHandle<C>> {
        return registry.index().byTopic[topic].orEmpty()
    }

    override fun all(): List<EventHandle<C>> {
        return registry.index().all
    }

    fun register(handle: EventHandle<C>) {
        registry.register(handle.key, handle)
    }

    fun <E : GameEvent> register(
        eventType: KClass<E>,
        order: Int = 0,
        key: EventHandleKey = EventHandleKey("event:${eventType.qualifiedName ?: eventType.simpleName}:$order"),
        handler: EventHandler<C, E>,
    ) {
        register(EventHandle.forEventType(eventType, order, key, handler))
    }

    inline fun <reified E : GameEvent> register(
        order: Int = 0,
        key: EventHandleKey = EventHandleKey("event:${E::class.qualifiedName ?: E::class.simpleName}:$order"),
        handler: EventHandler<C, E>,
    ) {
        register(E::class, order, key, handler)
    }

    fun registerTopic(
        topic: EventTopic,
        order: Int = 0,
        key: EventHandleKey = EventHandleKey("topic:${topic.path}:$order"),
        handler: EventHandler<C, GameEvent>,
    ) {
        register(EventHandle.forTopic(topic, order, key, handler))
    }

    override fun current(key: EventHandleKey): EventHandle<C>? {
        return registry.current(key)
    }

    override fun replace(
        key: EventHandleKey,
        value: EventHandle<C>,
        order: PatchOrder,
        scope: PatchRegistryMutationScope,
    ) {
        require(value.key == key) { "replacement event handle key ${value.key} must match registry key $key" }
        registry.replace(key, value, order, scope)
    }

    override fun remove(
        id: PatchId,
        scope: PatchRegistryMutationScope,
    ) {
        registry.remove(id, scope)
    }
}

internal class EventHandleIndex<C : HandlerContext>(
    handles: Iterable<EventHandle<C>>,
) {
    val all: List<EventHandle<C>> = handles.sortedBy(EventHandle<C>::order)
    val byEventType: Map<KClass<out GameEvent>, List<EventHandle<C>>> = all
        .filter { it.eventType != null }
        .groupBy { requireNotNull(it.eventType) }
    val byTopic: Map<EventTopic, List<EventHandle<C>>> = all
        .filter { it.topic != null }
        .groupBy { requireNotNull(it.topic) }
}

/**
 * Replaces event handler slots through the runtime patch lifecycle.
 */
val RuntimePatchInstallContext.eventHandlers: RuntimePatchEventHandlerReplacements
    get() = RuntimePatchEventHandlerReplacements(this)

class RuntimePatchEventHandlerReplacements internal constructor(
    private val context: RuntimePatchInstallContext,
) {
    fun <C : HandlerContext> replace(
        registry: PatchableEventHandleRegistry<C>,
        handle: EventHandle<C>,
    ) {
        context.replaceSlot(registry, handle.key, handle)
    }

    fun <C : HandlerContext, E : GameEvent> replaceEventType(
        registry: PatchableEventHandleRegistry<C>,
        eventType: KClass<E>,
        order: Int = 0,
        key: EventHandleKey = EventHandleKey("event:${eventType.qualifiedName ?: eventType.simpleName}:$order"),
        handler: EventHandler<C, E>,
    ) {
        replace(registry, EventHandle.forEventType(eventType, order, key, handler))
    }

    fun <C : HandlerContext> replaceTopic(
        registry: PatchableEventHandleRegistry<C>,
        topic: EventTopic,
        order: Int = 0,
        key: EventHandleKey = EventHandleKey("topic:${topic.path}:$order"),
        handler: EventHandler<C, GameEvent>,
    ) {
        replace(registry, EventHandle.forTopic(topic, order, key, handler))
    }
}

internal fun <C : HandlerContext> Iterable<EventHandle<C>>.associateByKey(): Map<EventHandleKey, EventHandle<C>> {
    val result = linkedMapOf<EventHandleKey, EventHandle<C>>()
    for (handle in this) {
        check(handle.key !in result) { "duplicate event handle key ${handle.key}" }
        result[handle.key] = handle
    }
    return result
}
