package io.github.mikai233.asteria.message

/**
 * Handles one concrete message type.
 *
 * Message handlers are registered explicitly instead of discovered by reflection. Business code or generated route code
 * should register each message type in a [PatchableMessageHandlerRegistry].
 */
fun interface MessageHandler<in M : Any> {
    fun handle(context: HandlerContext, message: M)
}
