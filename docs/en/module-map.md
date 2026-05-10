# Module Map

## Foundation

| Module                                                                     | Responsibility                                                                        | Use When                                                                                  |
|----------------------------------------------------------------------------|---------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| `foundation-core`                                                          | Application lifecycle, module system, roles, entity/singleton specs, service registry | Every Asteria application                                                                 |
| `foundation-actor`                                                         | Pekko actor base classes, actor-dispatcher coroutine scope, timer helpers             | Business actors need coroutines or lifecycle gates                                        |
| `foundation-contribution` / `foundation-contribution-ksp`                  | Generic contribution annotations and generated contribution lists                     | Business extension points need generated lists for custom indexes or patchable registries |
| `foundation-event`                                                         | In-process fact events, topic trees, ancestor fan-out, event handlers                 | Business modules need decoupled domain notifications                                      |
| `foundation-event-ksp`                                                     | Scans event handler annotations and generates event dispatchers                       | Event handler registration would otherwise be manual and large                            |
| `foundation-message`                                                       | Message contracts, handlers, dispatch, route registry                                 | The project wants framework-level message dispatch or generated registration              |
| `foundation-message-ksp`                                                   | Scans message handler annotations and generates registration code                     | Handler registration would otherwise be manual and large                                  |
| `foundation-message-gradle-plugin`                                         | Gradle integration for message KSP                                                    | Business modules want plugin-based codegen setup                                          |
| `foundation-protobuf`                                                      | Base protobuf message registry                                                        | Protobuf messages need a stable id registry                                               |
| `foundation-id`                                                            | Worker-id leases and Snowflake-style id generation                                    | Multiple nodes generate unique ids                                                        |
| `foundation-id-etcd` / `foundation-id-mongodb` / `foundation-id-zookeeper` | Worker-id lease backends                                                              | Pick the backend that matches the deployment                                              |

## Runtime and Communication

| Module                                                      | Responsibility                                                  | Use When                                                     |
|-------------------------------------------------------------|-----------------------------------------------------------------|--------------------------------------------------------------|
| `cluster-pekko`                                             | Pekko actor system, cluster sharding, singletons, entity wakeup | The server runs on a Pekko cluster                           |
| `cluster-pekko-management` / `cluster-pekko-kubernetes`     | Pekko Management or Kubernetes discovery                        | Different deployment discovery strategies                    |
| `cluster-config`                                            | Reads node topology from a config center                        | Node host, port, roles, or seeds come from the config center |
| `gateway-core`                                              | Gateway transport, session, and dispatch abstractions           | The service accepts client connections                       |
| `gateway-netty`                                             | WebSocket/binary Netty transport                                | The gateway is exposed through Netty                         |
| `gateway-pekko`                                             | Adapter from gateway routes to Pekko runtime                    | Gateway packets must be forwarded to actors                  |
| `broadcast-core` / `broadcast-protobuf` / `broadcast-pekko` | Local or Pekko broadcast plus protobuf payloads                 | The service needs node-local or cluster-wide notifications   |

## Protocol and RPC

| Module                           | Responsibility                                  | Use When                                                    |
|----------------------------------|-------------------------------------------------|-------------------------------------------------------------|
| `protocol-protobuf`              | Gateway protobuf envelope and protocol registry | Client protocol is protobuf                                 |
| `protobuf-codegen`               | Generates gateway/RPC protocol Kotlin files     | Protocol registries should not be handwritten               |
| `protobuf-codegen-gradle-plugin` | Gradle integration for protocol codegen         | Business projects generate protocol registries during build |
| `rpc-protobuf`                   | Protobuf RPC registry and entity-id registry    | Actor-to-actor RPC messages use protobuf                    |
| `rpc-protobuf-pekko`             | Pekko serializer and sharding extractors        | RPC messages cross node boundaries                          |

## Data and Config

| Module                                                                   | Responsibility                                                                | Use When                                                                       |
|--------------------------------------------------------------------------|-------------------------------------------------------------------------------|--------------------------------------------------------------------------------|
| `config-core`                                                            | Config snapshots, validation, derived components, hot reload, change dispatch | Business logic depends on config tables                                        |
| `config-annotations` / `config-ksp` / `config-gradle-plugin`             | Config table and config-change handler codegen                                | Strongly typed table access and generated handler lists are desired            |
| `config-luban`                                                           | Luban Java loader integration                                                 | Luban generates Java config classes                                            |
| `config-publisher`                                                       | Publishes config artifacts and manifests to a config center                   | Config bundles need versioned publication                                      |
| `config-center`                                                          | Config-center abstraction, typed repository, resilient watch wrappers         | Runtime config, cluster topology, or reload triggers live in an external store |
| `config-center-zookeeper` / `config-center-etcd` / `config-center-nacos` | Config-center backend adapters                                                | Pick the backend used by the deployment                                        |
| `persistence-core`                                                       | Actor-local data loading, caching, flushing, idle unload                      | Actors own their mutable in-memory data                                        |
| `persistence-mongodb-*`                                                  | Mongo annotations, KSP, tracked wrappers, write queues                        | Actor data is stored in MongoDB                                                |

## Operations

| Module                                                                           | Responsibility                                                       | Use When                                          |
|----------------------------------------------------------------------------------|----------------------------------------------------------------------|---------------------------------------------------|
| `script-core`                                                                    | Script engine, context, targets, execution results                   | GM or operational scripts are required            |
| `script-pekko`                                                                   | Maps script targets to Pekko nodes, roles, entities, and singletons  | Scripts need to run against actor runtime         |
| `script-job` / `script-job-mongodb`                                              | Async script jobs, persisted results, throttling, leases             | GM scripts may be long-running or multi-target    |
| `gm-core`                                                                        | GM feature metadata, permissions, audit context                      | The service has GM tools                          |
| `gm-shutdown`                                                                    | Business-side shutdown plans, phases, steps, and GM permissions      | GM or operations workflows trigger graceful stop  |
| `gm-*` starters                                                                  | Spring HTTP APIs and concrete feature adapters                       | GM operations are exposed over HTTP               |
| `ops-http-ktor`                                                                  | Node-local Ktor HTTP endpoint for SSH/curl script and patch controls | No GM node exists but local operations are needed |
| `patch-core` / `patch-jar` / `patch-mongodb` / `patch-zookeeper` / `patch-pekko` | Runtime patches, plugin resolution, repositories, cluster control    | Online runtime patches or patch audit are needed  |
| `observability-core` / `observability-opentelemetry`                             | Metrics/tracing abstractions and OTel implementation                 | The service reports observability data            |
| `starter-game-server-pekko`                                                      | Local and cluster startup DSL, route module, patch starter           | Business projects want less startup glue          |

## Suggested Combinations

A minimal single-process service only needs `foundation-core` and business modules. A Pekko cluster service usually
combines `foundation-core`, `foundation-actor`, `cluster-pekko`, `config-core`, and one config-center backend. A public
gateway adds `gateway-core`, `gateway-netty`, `protocol-protobuf`, and the protocol codegen plugin. GM script support
adds `script-core`, `script-pekko`, `script-job`, a repository implementation, and `gm-script`. If no GM node exists but
operators can SSH to game machines, add `ops-http-ktor` for a loopback HTTP control endpoint protected by a bearer
token.
