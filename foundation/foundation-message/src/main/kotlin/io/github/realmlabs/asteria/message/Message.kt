package io.github.realmlabs.asteria.message

/**
 * Marker interface for framework-routed messages.
 *
 * The interface is intentionally empty. Concrete transport or actor runtimes decide how messages are serialized,
 * delivered, and dispatched.
 */
interface Message

/**
 * Message that carries an entity id used for shard-style routing.
 *
 * This is a convention interface for business code and routing helpers; [MessageDispatcher] itself does not inspect it
 * automatically.
 */
interface ShardMessage<ID : Any> : Message {
    val id: ID
}
