package io.github.mikai233.asteria.message

import io.github.mikai233.asteria.patch.PatchInstallContext
import io.github.mikai233.asteria.patch.PatchSlotRegistry
import io.github.mikai233.asteria.patch.PatchableRegistry
import io.github.mikai233.asteria.patch.PatchId
import io.github.mikai233.asteria.patch.PatchOrder
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.createType
import kotlin.reflect.jvm.isAccessible

data class MessageHandle<M : Any>(
    val messageType: KClass<out M>,
    val handler: MessageHandler,
    val function: KFunction<*>,
    val expectsMessage: Boolean,
) {
    fun invoke(context: HandlerContext, message: M) {
        if (expectsMessage) {
            function.call(handler, context, message)
        } else {
            function.call(handler, context)
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
    constructor(
        baseMessageType: KClass<M>,
        handlers: Iterable<MessageHandler>,
    ) : this(PatchableMessageHandlerRegistry(baseMessageType, handlers))

    fun canDispatch(messageType: KClass<out M>): Boolean = handles.get(messageType) != null

    fun dispatch(context: HandlerContext, message: M) {
        dispatch(context, message::class, message)
    }

    fun dispatch(context: HandlerContext, messageType: KClass<out M>, message: M) {
        val handle = requireNotNull(handles.get(messageType)) {
            "handler for message ${messageType.qualifiedName} not found"
        }
        handle.invoke(context, message)
    }
}

/**
 * Handler registry whose message slots can be replaced by runtime patch layers.
 *
 * It is still useful without installing the patch module: reads are lock-free and the registry simply serves the base
 * handlers. When patch runtime is installed, [PatchInstallContext.replace] can replace individual message slots and
 * later rollback to the previous layer.
 */
class PatchableMessageHandlerRegistry<M : Any>(
    val baseMessageType: KClass<M>,
    handlers: Iterable<MessageHandler>,
) : MessageHandleRegistry<M>, PatchSlotRegistry<KClass<out M>, MessageHandle<M>> {
    private val registry = PatchableRegistry(scanHandlers(baseMessageType, handlers).associateBy { it.messageType })

    override fun get(messageType: KClass<out M>): MessageHandle<M>? {
        return registry.get(messageType)
    }

    override fun all(): Collection<MessageHandle<M>> {
        return registry.snapshot().values
    }

    fun scan(handler: MessageHandler): List<MessageHandle<M>> {
        return scanHandler(baseMessageType, handler)
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

fun <M : Any> scanHandlers(
    baseMessageType: KClass<M>,
    handlers: Iterable<MessageHandler>,
): List<MessageHandle<M>> {
    val seenTypes = mutableSetOf<KClass<out M>>()
    return handlers
        .flatMap { handler -> scanHandler(baseMessageType, handler) }
        .onEach { handle ->
            check(seenTypes.add(handle.messageType)) {
                "duplicate handler for message ${handle.messageType.qualifiedName}"
            }
        }
}

fun <M : Any> scanHandler(
    baseMessageType: KClass<M>,
    handler: MessageHandler,
): List<MessageHandle<M>> {
    return handler::class.declaredMemberFunctions.mapNotNull { function ->
        val annotation = function.findAnnotation<Handle>() ?: return@mapNotNull null
        val parameters = function.parameters
        check(parameters.size == 2 || parameters.size == 3) {
            "@Handle function ${function.name} must accept HandlerContext and optional message"
        }
        val messageType = when {
            parameters.size == 3 -> {
                val classifier = parameters.last().type.classifier
                check(classifier is KClass<*>) {
                    "@Handle function ${function.name} message parameter must be a class"
                }
                check(classifier.isSubclassOf(baseMessageType)) {
                    "${classifier.qualifiedName} must extend ${baseMessageType.qualifiedName}"
                }
                @Suppress("UNCHECKED_CAST")
                classifier as KClass<out M>
            }

            annotation.message != Any::class -> {
                check(annotation.message.isSubclassOf(baseMessageType)) {
                    "${annotation.message.qualifiedName} must extend ${baseMessageType.qualifiedName}"
                }
                @Suppress("UNCHECKED_CAST")
                annotation.message as KClass<out M>
            }

            else -> error("@Handle function ${function.name} without message parameter must declare message type")
        }
        val contextType = parameters[1].type
        check(HandlerContext::class.createType().isSubtypeOf(contextType)) {
            "@Handle function ${function.name} second parameter must accept HandlerContext"
        }
        function.isAccessible = true
        MessageHandle(messageType, handler, function, parameters.size == 3)
    }
}

/**
 * Replaces every [Handle] method declared by [handler].
 */
fun <M : Any> PatchInstallContext.replaceHandler(
    registry: PatchableMessageHandlerRegistry<M>,
    handler: MessageHandler,
) {
    registry.scan(handler).forEach { handle ->
        replace(registry, handle.messageType, handle)
    }
}
