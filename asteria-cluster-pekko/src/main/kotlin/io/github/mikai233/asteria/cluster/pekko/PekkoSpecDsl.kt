package io.github.mikai233.asteria.cluster.pekko

import io.github.mikai233.asteria.core.EntitySpec
import io.github.mikai233.asteria.core.EntitySpecBuilder
import io.github.mikai233.asteria.core.NodeRuntime
import io.github.mikai233.asteria.core.SingletonSpec
import io.github.mikai233.asteria.core.SingletonSpecBuilder
import org.apache.pekko.actor.Props
import org.apache.pekko.cluster.sharding.ShardCoordinator
import org.apache.pekko.cluster.sharding.ShardRegion

typealias EntityPropsFactory = (runtime: NodeRuntime, spec: EntitySpec<*>) -> Props
typealias SingletonPropsFactory = (runtime: NodeRuntime, spec: SingletonSpec) -> Props

/**
 * Controls how a sharded entity type is started on this node.
 *
 * Auto starts a real shard region when this node owns the entity role, otherwise a proxy.
 * Region requires this node to own the entity role and fails fast when it does not.
 * Proxy always starts a proxy region, even when this node owns the entity role.
 */
enum class PekkoShardStartup {
    Auto,
    Region,
    Proxy,
}

/**
 * Controls how a cluster singleton is started on this node.
 *
 * Auto starts the singleton manager when this node owns the singleton role, and always starts a proxy.
 * Host requires this node to own the singleton role and fails fast when it does not.
 * Proxy only starts the proxy, even when this node owns the singleton role.
 *
 * The registered singleton ref is always the proxy, because application messages should go through
 * ClusterSingletonProxy rather than the manager actor.
 */
enum class PekkoSingletonStartup {
    Auto,
    Host,
    Proxy,
}

fun <ID : Any> EntitySpecBuilder<ID>.actor(factory: EntityPropsFactory) {
    attribute(PEKKO_ENTITY_PROPS_FACTORY, factory)
}

fun <ID : Any> EntitySpecBuilder<ID>.extractor(extractor: ShardRegion.MessageExtractor) {
    attribute(PEKKO_ENTITY_EXTRACTOR, extractor)
}

fun <ID : Any> EntitySpecBuilder<ID>.allocationStrategy(strategy: ShardCoordinator.ShardAllocationStrategy) {
    attribute(PEKKO_ENTITY_ALLOCATION_STRATEGY, strategy)
}

fun <ID : Any> EntitySpecBuilder<ID>.shardStartup(startup: PekkoShardStartup) {
    attribute(PEKKO_ENTITY_SHARD_STARTUP, startup)
}

fun SingletonSpecBuilder.actor(factory: SingletonPropsFactory) {
    attribute(PEKKO_SINGLETON_PROPS_FACTORY, factory)
}

fun SingletonSpecBuilder.singletonStartup(startup: PekkoSingletonStartup) {
    attribute(PEKKO_SINGLETON_STARTUP, startup)
}

internal const val PEKKO_ENTITY_PROPS_FACTORY = "pekko.entity.propsFactory"
internal const val PEKKO_ENTITY_EXTRACTOR = "pekko.entity.extractor"
internal const val PEKKO_ENTITY_ALLOCATION_STRATEGY = "pekko.entity.allocationStrategy"
internal const val PEKKO_ENTITY_SHARD_STARTUP = "pekko.entity.shardStartup"
internal const val PEKKO_SINGLETON_PROPS_FACTORY = "pekko.singleton.propsFactory"
internal const val PEKKO_SINGLETON_STARTUP = "pekko.singleton.startup"

internal fun EntitySpec<*>.propsFactory(): EntityPropsFactory? {
    @Suppress("UNCHECKED_CAST")
    return attributes[PEKKO_ENTITY_PROPS_FACTORY] as? EntityPropsFactory
}

internal fun EntitySpec<*>.extractor(): ShardRegion.MessageExtractor? {
    return attributes[PEKKO_ENTITY_EXTRACTOR] as? ShardRegion.MessageExtractor
}

internal fun EntitySpec<*>.allocationStrategy(): ShardCoordinator.ShardAllocationStrategy? {
    return attributes[PEKKO_ENTITY_ALLOCATION_STRATEGY] as? ShardCoordinator.ShardAllocationStrategy
}

internal fun EntitySpec<*>.shardStartup(): PekkoShardStartup {
    return attributes[PEKKO_ENTITY_SHARD_STARTUP] as? PekkoShardStartup ?: PekkoShardStartup.Auto
}

internal fun SingletonSpec.propsFactory(): SingletonPropsFactory? {
    @Suppress("UNCHECKED_CAST")
    return attributes[PEKKO_SINGLETON_PROPS_FACTORY] as? SingletonPropsFactory
}

internal fun SingletonSpec.singletonStartup(): PekkoSingletonStartup {
    return attributes[PEKKO_SINGLETON_STARTUP] as? PekkoSingletonStartup ?: PekkoSingletonStartup.Auto
}
