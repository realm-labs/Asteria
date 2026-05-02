package io.github.mikai233.asteria.message

import io.github.mikai233.asteria.patch.PatchId
import io.github.mikai233.asteria.patch.PatchInstallContext
import io.github.mikai233.asteria.patch.PatchOrder
import io.github.mikai233.asteria.patch.PatchSlotRegistry
import io.github.mikai233.asteria.patch.PatchableRegistry
import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.metricsOrNoop
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * A statically registered message handler slot.
 */
class MessageHandle<M : Any> private constructor(
    val messageType: KClass<out M>,
    private val dispatch: (HandlerContext, M) -> Unit,
) {
    fun invoke(context: HandlerContext, message: M) {
        dispatch(context, message)
    }

    companion object {
        fun <M : Any, T : M> of(
            messageType: KClass<T>,
            handler: MessageHandler<T>,
        ): MessageHandle<M> {
            return MessageHandle(messageType) { context, message ->
                @Suppress("UNCHECKED_CAST")
                handler.handle(context, message as T)
            }
        }
    }
}

interface MessageHandleRegistry<M : Any> {
    fun get(messageType: KClass<out M>): MessageHandle<M>?

    fun all(): Collection<MessageHandle<M>>
}

class MessageDispatcher<M : Any>(
    private val handles: MessageHandleRegistry<M>,
) {
    private val logger = LoggerFactory.getLogger(MessageDispatcher::class.java)

    fun canDispatch(messageType: KClass<out M>): Boolean = handles.get(messageType) != null

    fun dispatch(context: HandlerContext, message: M) {
        dispatch(context, message::class, message)
    }

    fun dispatch(context: HandlerContext, messageType: KClass<out M>, message: M) {
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
 * Message handler registry whose message slots can be replaced by runtime patch layers.
 *
 * The registry is still useful without installing the patch module: reads are lock-free and the registry simply serves
 * the base handlers registered by business code or generated route code. When patch runtime is installed,
 * [PatchInstallContext.replace] can replace one message slot and later rollback to the previous layer.
 */
class PatchableMessageHandlerRegistry<M : Any>(
    handles: Iterable<MessageHandle<M>> = emptyList(),
) : MessageHandleRegistry<M>, PatchSlotRegistry<KClass<out M>, MessageHandle<M>> {
    private val registry = PatchableRegistry(handles.associateBy { it.messageType })

    override fun get(messageType: KClass<out M>): MessageHandle<M>? {
        return registry.get(messageType)
    }

    override fun all(): Collection<MessageHandle<M>> {
        return registry.snapshot().values
    }

    fun register(handle: MessageHandle<M>) {
        registry.register(handle.messageType, handle)
    }

    fun <T : M> register(
        messageType: KClass<T>,
        handler: MessageHandler<T>,
    ) {
        register(MessageHandle.of(messageType, handler))
    }

    inline fun <reified T : M> register(handler: MessageHandler<T>) {
        register(T::class, handler)
    }

    override fun current(key: KClass<out M>): MessageHandle<M>? {
        return registry.current(key)
    }

    override fun replace(key: KClass<out M>, value: MessageHandle<M>, order: PatchOrder) {
        registry.replace(key, value, order)
    }

    override fun remove(id: PatchId) {
        registry.remove(id)
    }
}

/**
 * Replaces the handler slot for [messageType].
 */
fun <M : Any, T : M> PatchInstallContext.replaceHandler(
    registry: PatchableMessageHandlerRegistry<M>,
    messageType: KClass<T>,
    handler: MessageHandler<T>,
) {
    replace(registry, messageType, MessageHandle.of(messageType, handler))
}

inline fun <M : Any, reified T : M> PatchInstallContext.replaceHandler(
    registry: PatchableMessageHandlerRegistry<M>,
    handler: MessageHandler<T>,
) {
    replaceHandler(registry, T::class, handler)
}
