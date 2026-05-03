package io.github.realmlabs.asteria.message

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.metricsOrNoop
import io.github.realmlabs.asteria.patch.*
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * A statically registered message handler slot.
 */
class MessageHandle<C : HandlerContext, M : Any> private constructor(
    val messageType: KClass<out M>,
    private val dispatch: (C, M) -> Unit,
) {
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

interface MessageHandleRegistry<C : HandlerContext, M : Any> {
    fun get(messageType: KClass<out M>): MessageHandle<C, M>?

    fun all(): Collection<MessageHandle<C, M>>
}

class MessageDispatcher<C : HandlerContext, M : Any>(
    private val handles: MessageHandleRegistry<C, M>,
) {
    private val logger = LoggerFactory.getLogger(MessageDispatcher::class.java)

    fun canDispatch(messageType: KClass<out M>): Boolean = handles.get(messageType) != null

    fun dispatch(context: C, message: M) {
        dispatch(context, message::class, message)
    }

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

fun <A : Any, M : Any> MessageDispatcher<ActorHandlerContext<A>, M>.dispatchActor(
    runtime: NodeRuntime,
    actor: A,
    message: M,
) {
    dispatch(DefaultActorHandlerContext(runtime, actor), message)
}

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
 * [PatchInstallContext.replace] can replace one message slot and later rollback to the previous layer.
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

    override fun replace(key: KClass<out M>, value: MessageHandle<C, M>, order: PatchOrder) {
        registry.replace(key, value, order)
    }

    override fun remove(id: PatchId) {
        registry.remove(id)
    }
}

/**
 * Replaces the handler slot for [messageType].
 */
fun <C : HandlerContext, M : Any, T : M> PatchInstallContext.replaceHandler(
    registry: PatchableMessageHandlerRegistry<C, M>,
    messageType: KClass<T>,
    handler: MessageHandler<C, T>,
) {
    replace(registry, messageType, MessageHandle.of(messageType, handler))
}

inline fun <C : HandlerContext, M : Any, reified T : M> PatchInstallContext.replaceHandler(
    registry: PatchableMessageHandlerRegistry<C, M>,
    handler: MessageHandler<C, T>,
) {
    replaceHandler(registry, T::class, handler)
}
