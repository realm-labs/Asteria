package io.github.realmlabs.asteria.patch.message

import io.github.realmlabs.asteria.message.HandlerContext
import io.github.realmlabs.asteria.message.MessageHandle
import io.github.realmlabs.asteria.message.MessageHandleRegistry
import io.github.realmlabs.asteria.message.MessageHandler
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

/**
 * Message handler registry for direct runtime replacement without patch lifecycle tracking.
 *
 * Use this for simple hotswap workflows where the caller owns validation and rollback. Replacements mutate the active
 * view immediately and are not recorded by [io.github.realmlabs.asteria.patch.PatchRuntime].
 */
class HotswapMessageHandlerRegistry<C : HandlerContext, M : Any>(
    handles: Iterable<MessageHandle<C, M>> = emptyList(),
) : MessageHandleRegistry<C, M> {
    private val handlesByMessageType = AtomicReference(handles.associateByMessageType())

    override fun get(messageType: KClass<out M>): MessageHandle<C, M>? {
        return handlesByMessageType.get()[messageType]
    }

    override fun all(): Collection<MessageHandle<C, M>> {
        return handlesByMessageType.get().values
    }

    fun snapshot(): Map<KClass<out M>, MessageHandle<C, M>> {
        return handlesByMessageType.get()
    }

    /**
     * Adds a new handler slot. Existing slots are rejected instead of overwritten.
     */
    fun register(handle: MessageHandle<C, M>) {
        update { current ->
            check(handle.messageType !in current) { "hotswap message handler ${handle.messageType} already exists" }
            current + (handle.messageType to handle)
        }
    }

    fun <T : M> register(
        messageType: KClass<T>,
        handler: MessageHandler<C, T>,
    ) {
        register(MessageHandle.of(messageType, handler))
    }

    inline fun <reified T : M> register(handler: MessageHandler<C, T>) {
        register(T::class, handler)
    }

    /**
     * Replaces an existing handler slot and returns the previous handle.
     */
    fun replace(handle: MessageHandle<C, M>): MessageHandle<C, M> {
        var previous: MessageHandle<C, M>? = null
        update { current ->
            previous = current[handle.messageType]
            check(previous != null) { "hotswap message handler ${handle.messageType} does not exist" }
            current + (handle.messageType to handle)
        }
        return requireNotNull(previous)
    }

    fun <T : M> replace(
        messageType: KClass<T>,
        handler: MessageHandler<C, T>,
    ): MessageHandle<C, M> {
        return replace(MessageHandle.of(messageType, handler))
    }

    inline fun <reified T : M> replace(handler: MessageHandler<C, T>): MessageHandle<C, M> {
        return replace(T::class, handler)
    }

    /**
     * Removes a handler slot and returns the removed handle, or `null` when absent.
     */
    fun remove(messageType: KClass<out M>): MessageHandle<C, M>? {
        var previous: MessageHandle<C, M>? = null
        update { current ->
            previous = current[messageType] ?: return@update current
            current - messageType
        }
        return previous
    }

    inline fun <reified T : M> remove(): MessageHandle<C, M>? {
        return remove(T::class)
    }

    private fun update(transform: (Map<KClass<out M>, MessageHandle<C, M>>) -> Map<KClass<out M>, MessageHandle<C, M>>) {
        while (true) {
            val current = handlesByMessageType.get()
            val updated = transform(current)
            if (updated === current || handlesByMessageType.compareAndSet(current, updated)) {
                return
            }
        }
    }
}

private fun <C : HandlerContext, M : Any> Iterable<MessageHandle<C, M>>.associateByMessageType():
        Map<KClass<out M>, MessageHandle<C, M>> = buildMap {
    for (handle in this@associateByMessageType) {
        check(put(handle.messageType, handle) == null) {
            "duplicate message handler ${handle.messageType}"
        }
    }
}
