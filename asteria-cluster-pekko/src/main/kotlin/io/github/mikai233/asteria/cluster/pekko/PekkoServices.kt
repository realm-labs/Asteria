package io.github.mikai233.asteria.cluster.pekko

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.SingletonName
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem

data class PekkoRuntime(
    val system: ActorSystem,
)

class EntityShardRegistry {
    private val shards: MutableMap<EntityKind, ActorRef> = linkedMapOf()

    fun register(kind: EntityKind, ref: ActorRef) {
        check(kind !in shards) { "entity shard $kind already registered" }
        shards[kind] = ref
    }

    operator fun get(kind: EntityKind): ActorRef {
        return requireNotNull(shards[kind]) { "entity shard $kind not found" }
    }

    fun find(kind: EntityKind): ActorRef? = shards[kind]

    fun all(): Map<EntityKind, ActorRef> = shards.toMap()
}

class SingletonActorRegistry {
    private val singletons: MutableMap<SingletonName, ActorRef> = linkedMapOf()

    fun register(name: SingletonName, ref: ActorRef) {
        check(name !in singletons) { "singleton $name already registered" }
        singletons[name] = ref
    }

    operator fun get(name: SingletonName): ActorRef {
        return requireNotNull(singletons[name]) { "singleton $name not found" }
    }

    fun find(name: SingletonName): ActorRef? = singletons[name]

    fun all(): Map<SingletonName, ActorRef> = singletons.toMap()
}
