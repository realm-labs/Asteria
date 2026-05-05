package io.github.realmlabs.asteria.message

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.SingletonName

/**
 * Minimal context shared by all message handlers.
 *
 * Keep this interface limited to data that exists for every dispatch location. Entity ids, singleton names, gateway
 * sessions and other runtime-specific values belong to narrower context interfaces so handlers can declare the exact
 * execution environment they need.
 */
interface HandlerContext {
    val runtime: NodeRuntime
}

data class DefaultHandlerContext(
    override val runtime: NodeRuntime,
) : HandlerContext

/**
 * Context for handlers running inside or on behalf of a sharded entity.
 *
 * [entityId] is intentionally typed as [Any] at this layer because dispatcher registries are usually shared across
 * many entity kinds with different id classes.
 */
interface EntityHandlerContext : HandlerContext {
    val entityKind: EntityKind
    val entityId: Any
}

data class DefaultEntityHandlerContext(
    override val runtime: NodeRuntime,
    override val entityKind: EntityKind,
    override val entityId: Any,
) : EntityHandlerContext

/**
 * Context for handlers running inside or on behalf of a singleton.
 */
interface SingletonHandlerContext : HandlerContext {
    val singletonName: SingletonName
}

data class DefaultSingletonHandlerContext(
    override val runtime: NodeRuntime,
    override val singletonName: SingletonName,
) : SingletonHandlerContext

/**
 * Context for handlers that are tied to a transport/session object.
 *
 * The framework keeps the session type generic because gateway/session implementations are transport- and
 * business-specific.
 */
interface SessionHandlerContext<S : Any> : HandlerContext {
    val session: S
}

data class DefaultSessionHandlerContext<S : Any>(
    override val runtime: NodeRuntime,
    override val session: S,
) : SessionHandlerContext<S>

/**
 * Context for handlers running inside or on behalf of an actor-like in-memory object.
 *
 * The framework keeps the actor type generic so business runtimes can expose their own actor base types without
 * introducing a dependency from `foundation-message` onto a specific actor framework.
 */
interface ActorHandlerContext<A : Any> : HandlerContext {
    val actor: A
}

data class DefaultActorHandlerContext<A : Any>(
    override val runtime: NodeRuntime,
    override val actor: A,
) : ActorHandlerContext<A>

typealias ActorMessageHandler<A, M> = MessageHandler<ActorHandlerContext<A>, M>

/**
 * Convenience alias for an actor-scoped handler registry.
 */
typealias ActorMessageHandlerRegistry<A, M> = PatchableMessageHandlerRegistry<ActorHandlerContext<A>, M>
