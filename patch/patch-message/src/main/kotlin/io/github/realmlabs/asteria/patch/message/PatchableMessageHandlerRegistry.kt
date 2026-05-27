package io.github.realmlabs.asteria.patch.message

import io.github.realmlabs.asteria.message.HandlerContext
import io.github.realmlabs.asteria.message.MessageHandle
import io.github.realmlabs.asteria.message.MessageHandleRegistry
import io.github.realmlabs.asteria.message.MessageHandler
import io.github.realmlabs.asteria.patch.PatchId
import io.github.realmlabs.asteria.patch.PatchOrder
import io.github.realmlabs.asteria.patch.PatchRegistryMutationScope
import io.github.realmlabs.asteria.patch.PatchSlotRegistry
import io.github.realmlabs.asteria.patch.PatchableRegistry
import io.github.realmlabs.asteria.patch.RuntimePatchInstallContext
import kotlin.reflect.KClass

/**
 * Message handler registry whose message slots can be replaced by runtime patch layers.
 */
class PatchableMessageHandlerRegistry<C : HandlerContext, M : Any>(
    handles: Iterable<MessageHandle<C, M>> = emptyList(),
) : MessageHandleRegistry<C, M>, PatchSlotRegistry<KClass<out M>, MessageHandle<C, M>> {
    private val registry = PatchableRegistry(handles.associateBy { it.messageType })

    override fun get(messageType: KClass<out M>): MessageHandle<C, M>? {
        return registry.get(messageType)
    }

    override fun all(): Collection<MessageHandle<C, M>> {
        return registry.snapshot().values
    }

    fun register(handle: MessageHandle<C, M>) {
        registry.register(handle.messageType, handle)
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

    override fun current(key: KClass<out M>): MessageHandle<C, M>? {
        return registry.current(key)
    }

    override fun replace(
        key: KClass<out M>,
        value: MessageHandle<C, M>,
        order: PatchOrder,
        scope: PatchRegistryMutationScope,
    ) {
        registry.replace(key, value, order, scope)
    }

    override fun remove(
        id: PatchId,
        scope: PatchRegistryMutationScope,
    ) {
        registry.remove(id, scope)
    }
}

/**
 * Replaces the handler slot for [messageType] through the runtime patch lifecycle.
 */
val RuntimePatchInstallContext.messageHandlers: RuntimePatchMessageHandlerReplacements
    get() = RuntimePatchMessageHandlerReplacements(this)

class RuntimePatchMessageHandlerReplacements internal constructor(
    private val context: RuntimePatchInstallContext,
) {
    fun <C : HandlerContext, M : Any, T : M> replace(
        registry: PatchableMessageHandlerRegistry<C, M>,
        messageType: KClass<T>,
        handler: MessageHandler<C, T>,
    ) {
        context.replaceSlot(registry, messageType, MessageHandle.of(messageType, handler))
    }
}

inline fun <C : HandlerContext, M : Any, reified T : M> RuntimePatchMessageHandlerReplacements.replace(
    registry: PatchableMessageHandlerRegistry<C, M>,
    handler: MessageHandler<C, T>,
) {
    replace(registry, T::class, handler)
}
