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
- `asteria-script-core`: optional script execution contracts, targets, contexts, engines, and results.
- `asteria-script-job`: optional async script job orchestration and result storage contracts for GM workflows.
- `asteria-script-protobuf`: protobuf wire contracts and converters for script commands and results.
- `asteria-script-pekko`: optional Pekko integration for node, role, actor path, entity, and singleton script targets.
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

Script execution is an opt-in extension:

```kotlin
install(ScriptModule {
    allowNodeScripts = true
    allowActorScripts = true
    maxArtifactBytes = 1024 * 1024
    engine(JarScriptEngine())
    auditSink(GameScriptAuditSink())
})
```

Actors must explicitly opt in to actor-level scripts:

```kotlin
class PlayerActor(runtime: NodeRuntime) : ScriptableAsteriaActor<NodeRuntime>(runtime)
```

Projects can replace the default script policy when script execution needs stricter controls such as checksum
allowlists, operator permissions, or external approvals.
Script execution is idempotent by `executionId`, execution scope, and concrete target. The default module uses an
in-memory execution store; production projects can replace it with a durable store:

```kotlin
install(ScriptModule {
    engine(JarScriptEngine())
    executionStore(RedisScriptExecutionStore())
})
```

```kotlin
val scripts = app.services.get<ScriptRuntime>()
val command = ScriptExecutionCommand(
    executionId = "fix-player-10001",
    target = ScriptTarget.Entity(EntityKind("player"), "10001"),
    artifact = artifact,
    metadata = ScriptExecutionMetadata(
        requester = "ops:mikai",
        reason = "fix stuck player state",
        attributes = mapOf("ticket" to "INC-10001"),
    ),
)
val result = scripts.execute(command)
```

GM-style async script jobs can be installed as a separate layer:

```kotlin
install(ScriptJobModule {
    store(DatabaseScriptJobStore())
})

val jobs = app.services.get<ScriptJobService>()
jobs.submit(command)
```

The first migration target is to make the existing `akka-game-server` a game project built on these modules, not the
source of framework-level concepts.
