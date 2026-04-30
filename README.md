# Asteria

Asteria is a modular Kotlin game server runtime. The framework owns runtime primitives such as modules, roles, entities,
message dispatch, cluster adapters, protocol codecs, gateway sessions, persistence contracts, and config contracts.
Game projects own their topology and domain model, so concepts like `World`, `Home`, `Player`, `Room`, or `Match` are
application-level choices instead of framework requirements.

## Modules

- `asteria-core`: application lifecycle, module system, role keys, entity specs, singleton specs, service registry.
- `asteria-actor`: Pekko actor base utilities, actor coroutine dispatcher, timer helpers.
- `asteria-message`: message contracts, handler dispatch, route registry, handler context.
- `asteria-rpc`: RPC target and route registry contracts.
- `asteria-rpc-protobuf`: protobuf RPC route registry runtime contracts for generated routes.
- `asteria-rpc-protobuf-generator`: descriptor-set based generator for protobuf RPC route registries.
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
        actor { runtime, _ -> PlayerActor.props(runtime) }
    }

    entity<Long>("match") {
        role("match")
        shardCount = 64
        handoffMessage = MatchHandoff
        actor { runtime, _ -> MatchActor.props(runtime) }
    }
}

app.launch()
```

RPC routes are intended to be generated from protobuf route metadata and loaded through `RpcModule.autoDiscover()`.
The runtime consumes `RpcRouteRegistry`; game projects should not have to register every message in application DSL.

```protobuf
import "asteria_rpc_options.proto";

message LoginReq {
  option (asteria.rpc.rpc_route) = {
    entity: {
      kind: "player"
      id_field: "player_id"
    }
  };

  int64 player_id = 1;
}
```

The protobuf route generator reads a descriptor set with `asteria.rpc.rpc_route` options and emits a
`GeneratedProtobufRpcRoutes` implementation plus the `ServiceLoader` metadata used by `RpcModule.autoDiscover()`.

The first migration target is to make the existing `akka-game-server` a game project built on these modules, not the
source of framework-level concepts.
