package io.github.realmlabs.asteria.cluster.pekko

import io.github.realmlabs.asteria.core.EntitySpec
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.SingletonSpec
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.apache.pekko.cluster.sharding.ClusterShardingSettings
import org.apache.pekko.cluster.sharding.ShardCoordinator
import org.apache.pekko.cluster.sharding.ShardRegion
import org.apache.pekko.cluster.singleton.ClusterSingletonManager
import org.apache.pekko.cluster.singleton.ClusterSingletonManagerSettings
import org.apache.pekko.cluster.singleton.ClusterSingletonProxy
import org.apache.pekko.cluster.singleton.ClusterSingletonProxySettings
import java.util.*

/**
 * Starts a Pekko shard region from an Asteria entity spec.
 *
 * The spec must provide actor props and a handoff message unless they are passed explicitly. Role restrictions are
 * applied to [ClusterShardingSettings] when the entity declares a role.
 */
fun ActorSystem.startAsteriaSharding(
    spec: EntitySpec<*>,
    props: Props,
    handoffMessage: Any = requireNotNull(spec.handoffMessage) {
        "handoffMessage for entity ${spec.kind} is required"
    },
    extractor: ShardRegion.MessageExtractor = PekkoShardExtractors.shardMessageByEntityIdHash(spec.shardCount),
    strategy: ShardCoordinator.ShardAllocationStrategy =
        ShardCoordinator.LeastShardAllocationStrategy(1, 10),
): ActorRef {
    val settings = ClusterShardingSettings.create(this).withOptionalRole(spec.role)
    return ClusterSharding.get(this).start(
        spec.kind.value,
        props,
        settings,
        extractor,
        strategy,
        handoffMessage,
    )
}

/**
 * Starts a proxy for an Asteria shard region.
 */
fun ActorSystem.startAsteriaShardingProxy(
    kind: String,
    role: RoleKey?,
    extractor: ShardRegion.MessageExtractor,
): ActorRef {
    return ClusterSharding.get(this).startProxy(kind, role.toOptionalRole(), extractor)
}

/**
 * Starts a Pekko cluster singleton manager from an Asteria singleton spec.
 */
fun ActorSystem.startAsteriaSingleton(
    spec: SingletonSpec,
    props: Props,
    handoffMessage: Any = requireNotNull(spec.handoffMessage) {
        "handoffMessage for singleton ${spec.name} is required"
    },
): ActorRef {
    val settings = ClusterSingletonManagerSettings.create(this).withRole(spec.role.value)
    return actorOf(ClusterSingletonManager.props(props, handoffMessage, settings), spec.name.value)
}

/**
 * Starts the application-facing proxy for an Asteria cluster singleton.
 */
fun ActorSystem.startAsteriaSingletonProxy(
    name: String,
    role: RoleKey,
): ActorRef {
    val settings = ClusterSingletonProxySettings.create(this).withRole(role.value)
    return actorOf(ClusterSingletonProxy.props("/user/$name", settings), "${name}Proxy")
}

private fun ClusterShardingSettings.withOptionalRole(role: RoleKey?): ClusterShardingSettings {
    return role?.let { withRole(it.value) } ?: this
}

private fun RoleKey?.toOptionalRole(): Optional<String> {
    return this?.let { Optional.of(it.value) } ?: Optional.empty()
}
