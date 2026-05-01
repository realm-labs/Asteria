# Asteria

Asteria is a modular Kotlin game server runtime. The framework owns runtime primitives such as modules, roles, entities,
message dispatch, cluster adapters, protocol codecs, gateway sessions, persistence contracts, and config contracts.
Game projects own their topology and domain model, so concepts like `World`, `Home`, `Player`, `Room`, or `Match` are
application-level choices instead of framework requirements.

## Modules

Foundation modules:

- `foundation/asteria-core`: application lifecycle, module system, role keys, entity specs, singleton specs, service registry.
- `foundation/asteria-actor`: Pekko actor base utilities, actor coroutine dispatcher, timer helpers.
- `foundation/asteria-message`: message contracts, handler dispatch, route registry, handler context.

RPC modules:

- `rpc/asteria-rpc`: RPC target and route registry contracts.
- `rpc/asteria-rpc-protobuf`: protobuf RPC route registry runtime contracts for generated routes.
- `rpc/asteria-rpc-protobuf-generator`: descriptor-set based generator for protobuf RPC route registries.

Script modules:

- `script/asteria-script-core`: optional script execution contracts, targets, contexts, engines, and results.
- `script/asteria-script-job`: optional async script job orchestration and result storage contracts for GM workflows.
- `script/asteria-script-protobuf`: protobuf wire contracts and converters for script commands and results.
- `script/asteria-script-pekko`: optional Pekko integration for node, role, actor path, entity, and singleton script targets.

GM modules:

- `gm/asteria-gm-core`: GM feature metadata, permission, scope, policy, and audit contracts.
- `gm/asteria-gm-spring-boot-starter`: Spring Boot starter that exposes installed GM features to HTTP clients.
- `gm/asteria-gm-script`: script execution GM feature metadata and script job operation contracts.
- `gm/asteria-gm-cluster`: runtime-neutral cluster status and actor query contracts for GM tools.
- `gm/asteria-gm-cluster-pekko`: Pekko-backed cluster status adapter for GM tools.

Config modules:

- `config/asteria-config`: config table snapshot, reload, validation, and module contracts.
- `config/asteria-config-luban`: optional Luban Java JSON and binary config loaders with module integration.
- `config/asteria-config-center`: config center store, watch, typed repository, codec, and in-memory implementation contracts.
- `config/asteria-config-center-zookeeper`: Zookeeper config center adapter backed by Apache Curator.
- `config/asteria-config-center-etcd`: Etcd config center adapter backed by jetcd.
- `config/asteria-config-center-nacos`: Nacos config center adapter backed by the official Nacos client.
- `config/asteria-cluster-config`: runtime node config, cluster topology, and config-center backed topology provider.

Observability modules:

- `observability/asteria-observability-core`: optional tracing, metrics, trace context, no-op defaults, and module registration.
- `observability/asteria-observability-opentelemetry`: OpenTelemetry bridge for Asteria observability.

Standalone modules:

- `asteria-cluster-pekko`: Pekko Cluster Sharding and Singleton adapters.
- `asteria-cluster-pekko-management`: optional Pekko Management / Cluster Bootstrap startup strategy.
- `asteria-cluster-pekko-kubernetes`: optional Kubernetes API discovery startup strategy.
- `asteria-protocol-protobuf`: protobuf ID registry and frame encoding contracts.
- `asteria-gateway-netty`: Netty gateway session and packet/protobuf codecs.
- `asteria-persistence`: entity, mem data, data scope, data manager, persistence provider contracts.
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

RPC entity ids are intended to be generated from protobuf metadata and loaded through `RpcModule.autoDiscover()`.
The runtime consumes `RpcEntityIdRegistry`; game projects should not have to register every sharded RPC message in the
application DSL.

```protobuf
import "asteria_rpc_options.proto";

message LoginReq {
  option (asteria.rpc.rpc_entity_id_field) = "player_id";

  int64 player_id = 1;
}
```

The protobuf entity id generator reads a descriptor set with `asteria.rpc.rpc_entity_id_field` options and emits a
`GeneratedProtobufRpcEntityIds` implementation plus the `ServiceLoader` metadata used by `RpcModule.autoDiscover()`.
Actor targets are selected by application code through the shard or singleton `ActorRef`; RPC metadata only describes how
cluster sharding extracts the entity id.

Observability is optional and defaults to no-op tracing and metrics:

```kotlin
val openTelemetry: OpenTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    .setMeterProvider(meterProvider)
    .build()

install(ObservabilityModule {
    val observability = openTelemetry.asAsteriaObservability("game-server")
    tracer = observability.tracer
    metrics = observability.metrics
})
```

Installed observability services are consumed by framework modules such as actor receive/ask, script execution, and script
jobs, so projects get baseline spans and metrics without changing game logic.

Config tables are exposed through a snapshot service. The core module does not care whether rows come from Luban,
Excel, JSON, a database, or generated project code; projects plug in a `ConfigLoader` and optional validators.

```kotlin
install(ConfigModule {
    loader(GameConfigLoader())
    validator { snapshot ->
        val items = snapshot.requireTable<Int, ItemConfig>(ConfigTableName("items"))
        items.all().forEach { item ->
            check(item.price >= 0, "price must not be negative", items.name, item.id)
        }
    }
})
```

Reload publishes a whole immutable `ConfigSnapshot`, so readers never observe half-loaded tables:

```kotlin
val configs = app.services.get<ConfigService>()
val item = configs.current()
    .requireTable<Int, ItemConfig>(ConfigTableName("items"))
    .require(1001)
```

Luban projects can install the optional Jackson JSON loader, or switch to the binary loader for production data, and keep
using generated `cfg.Tables` APIs:

```kotlin
install(LubanConfigModule {
    json()
    tables<cfg.Tables>()
    dataDir(Path.of("generated/json"))
})

install(LubanConfigModule {
    binary()
    preload(maxConcurrency = 4)
    tables<cfg.Tables>()
    dataDir(Path.of("generated/bytes"))
})

val tables = app.services.get<ConfigService>()
    .current()
    .requireComponent<cfg.Tables>()
val item = tables.tbItem.get(1001)
```

Luban loaders preload matching data files with bounded concurrency before constructing `cfg.Tables`; the generated
`Tables` object still controls table construction order and cross-table initialization.

Server runtime configuration is separated from the concrete config center. A `ConfigStore` owns byte-level get, children,
watch, put, and delete operations; `RuntimeConfigRepository` adds typed decoding through a pluggable `ConfigCodec`.

```kotlin
install(ConfigCenterModule {
    store(ZookeeperConfigStore(...))
})

install(ZookeeperConfigCenterModule {
    connectionString = "127.0.0.1:2181"
})

install(EtcdConfigCenterModule {
    endpoints("http://127.0.0.1:2379")
    keyPrefix = "/asteria"
})

install(NacosConfigCenterModule {
    serverAddr = "127.0.0.1:8848"
    dataIdPrefix = "asteria"
})

install(ClusterConfigModule {
    layout = ClusterConfigLayout.default("demo-game")
})
```

Pekko, gateway, database, and GM modules should consume typed services such as `ClusterTopologyProvider` instead of
depending on Zookeeper, Nacos, Etcd, or any other config center directly.
Nacos does not expose a native tree API, so the Nacos adapter maintains child indexes by convention when configs are
written through Asteria.

Pekko cluster runtime is driven by a startup strategy. `TopologyPekkoClusterStartup` selects the current node by
`nodeId`; Asteria generates the Pekko host, port, roles, and seed node config from `RuntimeNodeConfig`.
`LocalPekkoClusterStartup` self-joins a single local node. The core `asteria-cluster-pekko` module does not depend on
Pekko Management or Kubernetes Discovery. For dynamic environments, add `asteria-cluster-pekko-management` to use
`BootstrapPekkoClusterStartup`, or add `asteria-cluster-pekko-kubernetes` to use `KubernetesApiPekkoClusterStartup`.
Applications still provide deployment-specific network values such as canonical host and port through config or
`application.conf`.

```kotlin
val app = clusterGameApplication(nodeId = System.getenv("ASTERIA_NODE_ID")) {
    name = "demo-game"

    install(NacosConfigCenterModule {
        serverAddr = "127.0.0.1:8848"
        dataIdPrefix = "asteria"
    })

    entity<Long>("player") {
        role("player")
        handoffMessage = PlayerHandoff
        actor { runtime, _ -> PlayerActor.props(runtime) }
    }
}

app.launch()
```

For local files, use a static Typesafe config topology provider:

```hocon
asteria.cluster.nodes = [
  { node-id = "seed-1", host = "127.0.0.1", port = 2551, roles = ["seed"], seed = true },
  { node-id = "player-1", host = "127.0.0.1", port = 2552, roles = ["player"] }
]
```

```kotlin
val app = gameApplication {
    name = "demo-game"
    install(ClusterConfigModule {
        provider(TypesafeClusterTopologyProvider(ConfigFactory.load()))
    })
    install(PekkoRuntimeModule(TopologyPekkoClusterStartup(System.getenv("ASTERIA_NODE_ID"))))
}
```

```kotlin
install(
    PekkoRuntimeModule(
        KubernetesApiPekkoClusterStartup(
            roles = setOf(RoleKey("player")),
            serviceName = "asteria-player",
            namespace = "games",
            podLabelSelector = "app=asteria-player",
            requiredContactPointNr = 3,
        ),
    ),
)
```

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
