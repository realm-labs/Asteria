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

fun <ID : Any> EntitySpecBuilder<ID>.actor(factory: EntityPropsFactory) {
    attribute(PEKKO_ENTITY_PROPS_FACTORY, factory)
}

fun <ID : Any> EntitySpecBuilder<ID>.extractor(extractor: ShardRegion.MessageExtractor) {
    attribute(PEKKO_ENTITY_EXTRACTOR, extractor)
}

fun <ID : Any> EntitySpecBuilder<ID>.allocationStrategy(strategy: ShardCoordinator.ShardAllocationStrategy) {
    attribute(PEKKO_ENTITY_ALLOCATION_STRATEGY, strategy)
}

fun SingletonSpecBuilder.actor(factory: SingletonPropsFactory) {
    attribute(PEKKO_SINGLETON_PROPS_FACTORY, factory)
}

internal const val PEKKO_ENTITY_PROPS_FACTORY = "pekko.entity.propsFactory"
internal const val PEKKO_ENTITY_EXTRACTOR = "pekko.entity.extractor"
internal const val PEKKO_ENTITY_ALLOCATION_STRATEGY = "pekko.entity.allocationStrategy"
internal const val PEKKO_SINGLETON_PROPS_FACTORY = "pekko.singleton.propsFactory"

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

internal fun SingletonSpec.propsFactory(): SingletonPropsFactory? {
    @Suppress("UNCHECKED_CAST")
    return attributes[PEKKO_SINGLETON_PROPS_FACTORY] as? SingletonPropsFactory
}
