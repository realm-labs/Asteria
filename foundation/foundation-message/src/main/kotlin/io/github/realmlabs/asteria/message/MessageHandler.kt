package io.github.realmlabs.asteria.message

/**
 * Handles one concrete message type.
 *
 * Message handlers are registered explicitly instead of discovered by reflection. Business code or generated route code
 * should register each message type in a [PatchableMessageHandlerRegistry].
 */
fun interface MessageHandler<in C : HandlerContext, in M : Any> {
    fun handle(context: C, message: M)
}
