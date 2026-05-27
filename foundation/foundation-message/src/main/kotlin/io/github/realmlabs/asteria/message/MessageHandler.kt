package io.github.realmlabs.asteria.message

/**
 * Handles one concrete message type.
 *
 * Message handlers are registered explicitly instead of discovered by reflection. Business code or generated route code
 * should publish each handler through a [MessageHandleRegistry].
 *
 * Handler dispatch is exact-type based: a handler registered for a base class does not automatically receive subclass
 * instances unless those subclass messages are dispatched with the base [kotlin.reflect.KClass] explicitly.
 */
fun interface MessageHandler<in C : HandlerContext, in M : Any> {
    fun handle(context: C, message: M)
}
