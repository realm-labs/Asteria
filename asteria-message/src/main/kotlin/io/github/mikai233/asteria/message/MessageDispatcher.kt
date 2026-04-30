package io.github.mikai233.asteria.message

import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.createType

private data class MessageHandle<M : Any>(
    val messageType: KClass<out M>,
    val handler: MessageHandler,
    val function: KFunction<*>,
    val expectsMessage: Boolean,
)

class MessageDispatcher<M : Any>(
    private val baseMessageType: KClass<M>,
    handlers: Iterable<MessageHandler>,
) {
    private val seenTypes: MutableSet<KClass<out M>> = mutableSetOf()

    private val handles: Map<KClass<out M>, MessageHandle<M>> = handlers
        .flatMap(::scanHandler)
        .associateBy {
            checkNoDuplicate(it.messageType)
            it.messageType
        }

    private fun checkNoDuplicate(messageType: KClass<out M>): KClass<out M> {
        check(seenTypes.add(messageType)) {
            "duplicate handler for message ${messageType.qualifiedName}"
        }
        return messageType
    }

    private fun scanHandler(handler: MessageHandler): List<MessageHandle<M>> {
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
            MessageHandle(messageType, handler, function, parameters.size == 3)
        }
    }

    fun canDispatch(messageType: KClass<out M>): Boolean = messageType in handles

    fun dispatch(context: HandlerContext, message: M) {
        dispatch(context, message::class, message)
    }

    fun dispatch(context: HandlerContext, messageType: KClass<out M>, message: M) {
        val handle = requireNotNull(handles[messageType]) {
            "handler for message ${messageType.qualifiedName} not found"
        }
        if (handle.expectsMessage) {
            handle.function.call(handle.handler, context, message)
        } else {
            handle.function.call(handle.handler, context)
        }
    }
}
