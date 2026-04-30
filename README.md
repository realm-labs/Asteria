# Asteria

Asteria is a modular Kotlin game server runtime. The framework owns runtime primitives such as modules, roles, entities,
message dispatch, cluster adapters, protocol codecs, gateway sessions, persistence contracts, and config contracts.
Game projects own their topology and domain model, so concepts like `World`, `Home`, `Player`, `Room`, or `Match` are
application-level choices instead of framework requirements.

## Modules

- `asteria-core`: application lifecycle, module system, role keys, entity specs, singleton specs, service registry.
- `asteria-actor`: Pekko actor base utilities, actor coroutine dispatcher, timer helpers.
- `asteria-message`: message contracts, handler dispatch, route registry, handler context.
- `asteria-cluster-pekko`: Pekko Cluster Sharding and Singleton adapters.
- `asteria-protocol-protobuf`: protobuf ID registry and frame encoding contracts.
- `asteria-gateway-netty`: Netty gateway session and packet/protobuf codecs.
- `asteria-persistence`: entity, mem data, data scope, data manager, persistence provider contracts.
- `asteria-config`: config provider and watch contracts.
- `asteria-starter`: starter DSL helpers for local projects.

## Minimal Shape

```kotlin
val app = localGameApplication {
    name = "demo-game"

    role("gateway")
    role("player")
    role("match")

    entity<Long>("player") {
        role("player")
        shardCount = 128
        handoffMessage = PlayerHandoff
    }

    entity<Long>("match") {
        role("match")
        shardCount = 64
        handoffMessage = MatchHandoff
    }

    routes {
        route<LoginReq>(RouteTarget.Entity(EntityKind("player"))) { it.playerId }
        route<JoinMatchReq>(RouteTarget.Entity(EntityKind("match"))) { it.matchId }
    }
}

app.launch()
```

The first migration target is to make the existing `akka-game-server` a game project built on these modules, not the
source of framework-level concepts.
