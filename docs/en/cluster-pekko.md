# Pekko Cluster

`cluster-pekko` maps roles, entities, and singletons declared in `foundation-core` to a Pekko actor system, cluster
sharding, and cluster singletons.

## Starting the Runtime

```kotlin
val app = gameApplication {
    name = "game"

    role("world")

    entity<Long>("world") {
        role = RoleKey("world")
    }

    install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
}

app.launch()
```

Production projects usually read the current node host, port, roles, and seed nodes through `cluster-config`, then pass
them to the Pekko runtime. Startup topology is process startup input; changes usually take effect through rolling
restarts.

During `install`, `PekkoRuntimeModule` calls `PekkoClusterStartup.resolve()` to get a startup plan, creates the
`ActorSystem`, updates the concrete node roles from the plan, and registers `PekkoRuntime`, `ActorSystem`,
`RuntimeNodeConfig`, `ClusterTopology`, `EntityShardRegistry`, and `SingletonActorRegistry`. During `start`, it reads
the declared entity/singleton topology and starts sharding, singleton hosts, or proxies.

`TopologyPekkoClusterStartup` reads the full topology from `ClusterTopologyProvider.current()`, selects the current node
by `nodeId`, validates that declared application roles are covered by the topology, then uses
`PekkoClusterConfig.build()` to derive Pekko host, port, roles, and seed-node config. `watch()` is available for tools
that display topology changes, but the default Pekko startup path does not hot-apply topology into an already-created
ActorSystem.

## Entities and Sharding

The entity `kind` declared in the application must match the business message extractor and entity actor registration.
Messages sent to sharding must expose an entity id to the extractor.

```kotlin
data class WorldWakeupReq(val worldId: Long) : Serializable

val extractor = PekkoShardExtractors.longId<WorldWakeupReq>(
    entityId = { it.worldId },
)
```

Concrete actor props, extractors, and startup wiring are owned by business modules. The framework provides topology and
shared helper types.

Startup mode decides what reference this node obtains:

- `Auto`: start a shard region when the node owns the entity role; otherwise start a proxy.
- `Region`: require the entity role and fail startup when the role is missing.
- `Proxy`: always start a proxy.

If an entity does not declare an extractor, the runtime uses the default entity-id hash extractor. Starting a region
requires actor props; starting a proxy only needs kind, role, and extractor. The resulting reference is registered in
`EntityShardRegistry` so downstream forwarding modules can look it up by kind.

For singletons, `Auto` starts the host only on nodes with the target role, `Host` enforces that role, and `Proxy` skips
hosting. The runtime always starts and registers a singleton proxy in `SingletonActorRegistry`, including on host nodes.

## Entity Wakeup

`PekkoEntityWakerModule` is intended for long-lived entities that should be started after cluster boot, such as World
actors. It reads the full desired target set from a source, asks entities that have not completed yet, and adjusts
concurrency by recent success rate.

```kotlin
install(PekkoEntityWakerModule {
    task<Long>("world") {
        kind("world")

        readiness = PekkoEntityWakeReadiness(
            role = RoleKey("world"),
            minUpRatio = 0.8,
        )

        concurrency = PekkoEntityWakeConcurrency(
            initial = 20,
            min = 5,
            max = 80,
        )

        retry = PekkoEntityWakeRetry(
            maxAttempts = 10,
            exhaustedDelay = 10.minutes,
        )

        targets {
            services.get<WorldConfigService>().worldIds()
        }

        message { worldId -> WorldWakeupReq(worldId) }

        success { response ->
            response is WorldWakeupResp && response.ok
        }
    }
})
```

The source returns the full current target set, not a delta. After config reload, the coordinator reconciles the source
and queues only new or still-unfinished targets.

## Cluster View

`cluster-config` provides `ClusterViewService`, which describes the nodes expected by configuration or service
discovery together with their current runtime status. When `ClusterTopology` is available, the Pekko runtime registers
`PekkoClusterViewService` and merges configured topology with the current Pekko member view:

- Configured nodes that are visible as current members are marked `Reachable`.
- Configured nodes that are not visible as current members still appear in the snapshot and are marked `Expected`.
- Current members that are not in configuration also appear with `configured=false`.

This view is the shared input for GM cluster status, script target planning, and patch target planning. Pekko active
members only describe what the runtime currently sees; they do not replace the global expected node list.

## GM Control

The entity waker exposes control APIs for GM and operations tools:

```kotlin
val waker = services.get<PekkoEntityWaker>()

val status = waker.status("world")
waker.wake("world", listOf("1001", "1002"))
waker.cancel("world", listOf("1003"), reason = "bad world data")
waker.reconcile("world")
```

`status` shows pending, in-flight, completed, failed, and exhausted targets. For bad actors that fail forever, cancel
the target first, fix data, then manually wake or let the source reconcile again.

## Failure and Retry

The default retry model is not an infinite tight loop. A target is marked exhausted after `maxAttempts`; if
`exhaustedDelay` is configured, it is retried later at a lower frequency. Fixed data or code failures should be
cancelled by GM, otherwise low-frequency retries will continue to create noise.

Wake messages must be idempotent. Coordinator restarts, source changes, and manual GM actions can wake the same entity
id again.

## Serialization

Cross-node control messages and status responses must not rely on Java default serialization. `cluster-pekko` includes
an explicit serializer for entity-waker control messages; new public control messages must update the serializer and
`reference.conf`.
