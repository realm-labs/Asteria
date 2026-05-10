package io.github.realmlabs.asteria.message

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.metricsOrNoop
import io.github.realmlabs.asteria.patch.*
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * A statically registered message handler slot.
 *
 * A handle erases the concrete handler message subtype into the dispatcher's common message type [M], while preserving
 * the exact [messageType] used for lookup and diagnostics.
 */
class MessageHandle<C : HandlerContext, M : Any> private constructor(
    val messageType: KClass<out M>,
    private val dispatch: (C, M) -> Unit,
) {
    /**
     * Invokes the bound handler without additional lookup.
     */
    fun invoke(context: C, message: M) {
        dispatch(context, message)
    }

    companion object {
        fun <C : HandlerContext, M : Any, T : M> of(
            messageType: KClass<T>,
            handler: MessageHandler<C, T>,
        ): MessageHandle<C, M> {
            return MessageHandle(messageType) { context, message ->
                @Suppress("UNCHECKED_CAST")
                handler.handle(context, message as T)
            }
        }
    }
}

/**
 * Read-only message handle registry.
 *
 * Dispatchers only depend on this minimal interface so they can work with static registries, patchable registries, or
 * generated registry adapters.
 */
interface MessageHandleRegistry<C : HandlerContext, M : Any> {
    fun get(messageType: KClass<out M>): MessageHandle<C, M>?

    fun all(): Collection<MessageHandle<C, M>>
}

/**
 * Exact-type message dispatcher.
 *
 * Message lookup is by the supplied [KClass] only. There is no polymorphic search through superclasses or interfaces.
 * Missing handlers and handler exceptions are propagated to the caller after metrics and logs are recorded.
 */
class MessageDispatcher<C : HandlerContext, M : Any>(
    private val handles: MessageHandleRegistry<C, M>,
) {
    private val logger = LoggerFactory.getLogger(MessageDispatcher::class.java)

    /**
     * Returns whether a handler is registered for the exact [messageType].
     */
    fun canDispatch(messageType: KClass<out M>): Boolean = handles.get(messageType) != null

    /**
     * Dispatches [message] using its runtime class as the lookup key.
     */
    fun dispatch(context: C, message: M) {
        dispatch(context, message::class, message)
    }

    /**
     * Dispatches [message] using the explicit [messageType] lookup key.
     *
     * This overload is useful when callers intentionally register handlers against an abstract base message type.
     */
    fun dispatch(context: C, messageType: KClass<out M>, message: M) {
        val metrics = context.runtime.metricsOrNoop()
        val tags = MetricTags.of(
            "runtime" to context.runtime.name,
            "message" to (messageType.qualifiedName ?: messageType.simpleName ?: "unknown"),
        )
        val startedAt = System.nanoTime()
        metrics.counter("asteria.message.dispatch.total", tags).increment()
        val handle = requireNotNull(handles.get(messageType)) {
            "handler for message ${messageType.qualifiedName} not found"
        }
        try {
            handle.invoke(context, message)
        } catch (error: Throwable) {
            metrics.counter("asteria.message.dispatch.failed.total", tags).increment()
            logger.error(
                "message dispatch failed runtime={} message={}",
                context.runtime.name,
                messageType.qualifiedName,
                error,
            )
            throw error
        } finally {
            metrics.timer("asteria.message.dispatch.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }
}

/**
 * Convenience helper that builds an [ActorHandlerContext] and dispatches one message.
 */
fun <A : Any, M : Any> MessageDispatcher<ActorHandlerContext<A>, M>.dispatchActor(
    runtime: NodeRuntime,
    actor: A,
    message: M,
) {
    dispatch(DefaultActorHandlerContext(runtime, actor), message)
}

/**
 * Convenience helper that builds an [ActorHandlerContext] and dispatches one message with an explicit lookup type.
 */
fun <A : Any, M : Any> MessageDispatcher<ActorHandlerContext<A>, M>.dispatchActor(
    runtime: NodeRuntime,
    actor: A,
    messageType: KClass<out M>,
    message: M,
) {
    dispatch(DefaultActorHandlerContext(runtime, actor), messageType, message)
}

/**
 * Message handler registry whose message slots can be replaced by runtime patch layers.
 *
 * The registry is still useful without installing the patch module: reads are lock-free and the registry simply serves
 * the base handlers registered by business code or generated route code. When patch runtime is installed,
 * [RuntimePatchInstallContext.messageHandlers] can replace one message slot and later rollback to the previous layer.
 */
class PatchableMessageHandlerRegistry<C : HandlerContext, M : Any>(
    handles: Iterable<MessageHandle<C, M>> = emptyList(),
) : MessageHandleRegistry<C, M>, PatchSlotRegistry<KClass<out M>, MessageHandle<C, M>> {
    private val registry = PatchableRegistry(handles.associateBy { it.messageType })

    /**
     * Returns the currently active handler for [messageType].
     *
     * This is the same effective value that [MessageDispatcher] will dispatch to after applying any active patch
     * layers.
     */
    override fun get(messageType: KClass<out M>): MessageHandle<C, M>? {
        return registry.get(messageType)
    }

    /**
     * Returns a snapshot of all currently active handler slots.
     *
     * The returned collection reflects patch replacements that are currently in effect, not just the original base
     * registrations.
     */
    override fun all(): Collection<MessageHandle<C, M>> {
        return registry.snapshot().values
    }

    /**
     * Adds a new base handler slot for [handle.messageType].
     *
     * This mutates the registry's base layer, which is the long-lived definition used before any patch layers are
     * applied. It is intended for normal application startup and generated handler wiring.
     *
     * Unlike [replace], this API does not create a patch layer and is not reversible through [remove]. If the base key
     * already exists, the call fails instead of silently overwriting it.
     */
    fun register(handle: MessageHandle<C, M>) {
        registry.register(handle.messageType, handle)
    }

    /**
     * Adds a new base handler slot for one concrete [messageType].
     */
    fun <T : M> register(
        messageType: KClass<T>,
        handler: MessageHandler<C, T>,
    ) {
        register(MessageHandle.of(messageType, handler))
    }

    /**
     * Reified convenience overload for base-layer registration.
     */
    inline fun <reified T : M> register(handler: MessageHandler<C, T>) {
        register(T::class, handler)
    }

    /**
     * Returns the currently effective slot for [key] from the active view.
     *
     * Patch runtime uses this to validate that a key exists before installing a replacement. In practice it reads the
     * same effective value as [get], which may already come from another patch layer rather than from the base layer.
     */
    override fun current(key: KClass<out M>): MessageHandle<C, M>? {
        return registry.current(key)
    }

    /**
     * Installs or updates one patch replacement layer for [key].
     *
     * This does not mutate the base registry entry created by [register]. Instead it overlays a patch-owned handler in
     * the layer identified by [order]. When several layers target the same message type, patch ordering decides which
     * one becomes active.
     *
     * The key must already exist in the base registry; patches can replace an existing slot but cannot introduce a
     * brand-new message type.
     */
    override fun replace(
        key: KClass<out M>,
        value: MessageHandle<C, M>,
        order: PatchOrder,
        scope: PatchRegistryMutationScope,
    ) {
        registry.replace(key, value, order, scope)
    }

    /**
     * Removes every patch layer owned by [id].
     *
     * Base registrations added through [register] are untouched. After removal, the effective handler for each affected
     * key falls back to the next remaining patch layer or to the base handler when no patch layer is left.
     */
    override fun remove(
        id: PatchId,
        scope: PatchRegistryMutationScope,
    ) {
        registry.remove(id, scope)
    }
}

/**
 * Replaces the handler slot for [messageType].
 *
 * Replacement is scoped to one message type and composes with the patch ordering rules of the underlying patch
 * runtime.
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
