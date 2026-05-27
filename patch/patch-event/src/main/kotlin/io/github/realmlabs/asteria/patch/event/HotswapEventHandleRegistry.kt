package io.github.realmlabs.asteria.patch.event

import io.github.realmlabs.asteria.event.EventHandle
import io.github.realmlabs.asteria.event.EventHandleKey
import io.github.realmlabs.asteria.event.EventHandleRegistry
import io.github.realmlabs.asteria.event.EventHandler
import io.github.realmlabs.asteria.event.EventTopic
import io.github.realmlabs.asteria.event.GameEvent
import io.github.realmlabs.asteria.message.HandlerContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

/**
 * Event-handle registry for direct runtime replacement without patch lifecycle tracking.
 *
 * Use this for simple hotswap workflows where the caller owns validation and rollback. Replacements mutate the active
 * view immediately and are not recorded by [io.github.realmlabs.asteria.patch.PatchRuntime].
 */
class HotswapEventHandleRegistry<C : HandlerContext>(
    handles: Iterable<EventHandle<C>> = emptyList(),
) : EventHandleRegistry<C> {
    private val state = AtomicReference(HotswapEventRegistryState(handles.associateByKey()))

    override fun handlersFor(eventType: KClass<out GameEvent>): List<EventHandle<C>> {
        return state.get().index.byEventType[eventType].orEmpty()
    }

    override fun handlersFor(topic: EventTopic): List<EventHandle<C>> {
        return state.get().index.byTopic[topic].orEmpty()
    }

    override fun all(): List<EventHandle<C>> {
        return state.get().index.all
    }

    fun snapshot(): Map<EventHandleKey, EventHandle<C>> {
        return state.get().handles
    }

    /**
     * Adds a new handler slot. Existing slots are rejected instead of overwritten.
     */
    fun register(handle: EventHandle<C>) {
        update { current ->
            check(handle.key !in current) { "hotswap event handle key ${handle.key} already exists" }
            current + (handle.key to handle)
        }
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

    /**
     * Replaces an existing handler slot and returns the previous handle.
     */
    fun replace(handle: EventHandle<C>): EventHandle<C> {
        var previous: EventHandle<C>? = null
        update { current ->
            previous = current[handle.key]
            check(previous != null) { "hotswap event handle key ${handle.key} does not exist" }
            current + (handle.key to handle)
        }
        return requireNotNull(previous)
    }

    fun <E : GameEvent> replaceEventType(
        eventType: KClass<E>,
        order: Int = 0,
        key: EventHandleKey = EventHandleKey("event:${eventType.qualifiedName ?: eventType.simpleName}:$order"),
        handler: EventHandler<C, E>,
    ): EventHandle<C> {
        return replace(EventHandle.forEventType(eventType, order, key, handler))
    }

    inline fun <reified E : GameEvent> replaceEventType(
        order: Int = 0,
        key: EventHandleKey = EventHandleKey("event:${E::class.qualifiedName ?: E::class.simpleName}:$order"),
        handler: EventHandler<C, E>,
    ): EventHandle<C> {
        return replaceEventType(E::class, order, key, handler)
    }

    fun replaceTopic(
        topic: EventTopic,
        order: Int = 0,
        key: EventHandleKey = EventHandleKey("topic:${topic.path}:$order"),
        handler: EventHandler<C, GameEvent>,
    ): EventHandle<C> {
        return replace(EventHandle.forTopic(topic, order, key, handler))
    }

    /**
     * Removes a handler slot and returns the removed handle, or `null` when absent.
     */
    fun remove(key: EventHandleKey): EventHandle<C>? {
        var previous: EventHandle<C>? = null
        update { current ->
            previous = current[key] ?: return@update current
            current - key
        }
        return previous
    }

    private fun update(
        transform: (Map<EventHandleKey, EventHandle<C>>) -> Map<EventHandleKey, EventHandle<C>>,
    ) {
        while (true) {
            val current = state.get()
            val updatedHandles = transform(current.handles)
            if (updatedHandles === current.handles) {
                return
            }
            val updated = HotswapEventRegistryState(updatedHandles)
            if (state.compareAndSet(current, updated)) {
                return
            }
        }
    }

    private class HotswapEventRegistryState<C : HandlerContext>(
        val handles: Map<EventHandleKey, EventHandle<C>>,
    ) {
        val index: EventHandleIndex<C> = EventHandleIndex(handles.values)
    }
}
