package io.github.mikai233.asteria.cluster.pekko

import io.github.mikai233.asteria.rpc.MissingRpcRouteException
import io.github.mikai233.asteria.rpc.RpcRouteRegistry
import io.github.mikai233.asteria.rpc.RpcTarget
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem

class PekkoRpcRouter(
    private val system: ActorSystem,
    private val routeRegistry: RpcRouteRegistry,
    private val entityShards: EntityShardRegistry,
    private val singletons: SingletonActorRegistry,
) {
    fun route(message: Any, sender: ActorRef = ActorRef.noSender()): RpcTarget {
        val target = routeRegistry.resolve(message) ?: throw MissingRpcRouteException(message)
        when (target) {
            is RpcTarget.Entity -> entityShards[target.kind].tell(message, sender)
            is RpcTarget.Singleton -> singletons[target.name].tell(message, sender)
            is RpcTarget.Service -> system.actorSelection(target.path).tell(message, sender)
            RpcTarget.Local -> system.eventStream().publish(message)
        }
        return target
    }
}
