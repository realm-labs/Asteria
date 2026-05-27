package io.github.realmlabs.asteria.message

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.metricsOrNoop
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
