package io.github.mikai233.asteria.message

interface MessageHandler

class MessageHandlerRegistry(
    handlers: Iterable<MessageHandler> = emptyList(),
) {
    private val handlersByType: MutableMap<Class<out MessageHandler>, MessageHandler> = linkedMapOf()

    init {
        handlers.forEach(::register)
    }

    fun register(handler: MessageHandler) {
        handlersByType[handler.javaClass] = handler
    }

    fun all(): Collection<MessageHandler> = handlersByType.values
}
