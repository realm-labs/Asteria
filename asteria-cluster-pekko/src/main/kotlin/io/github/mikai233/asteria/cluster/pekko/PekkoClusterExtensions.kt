package io.github.mikai233.asteria.cluster.pekko

import io.github.mikai233.asteria.core.EntitySpec
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonSpec
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
import java.util.Optional

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
    val settings = ClusterShardingSettings.create(this).withRole(spec.role.value)
    return ClusterSharding.get(this).start(
        spec.kind.value,
        props,
        settings,
        extractor,
        strategy,
        handoffMessage,
    )
}

fun ActorSystem.startAsteriaShardingProxy(
    kind: String,
    role: RoleKey,
    extractor: ShardRegion.MessageExtractor,
): ActorRef {
    return ClusterSharding.get(this).startProxy(kind, Optional.of(role.value), extractor)
}

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

fun ActorSystem.startAsteriaSingletonProxy(
    name: String,
    role: RoleKey,
): ActorRef {
    val settings = ClusterSingletonProxySettings.create(this).withRole(role.value)
    return actorOf(ClusterSingletonProxy.props("/user/$name", settings), "${name}Proxy")
}
