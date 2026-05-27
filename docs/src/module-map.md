# Module Map

## Foundation

| Module                                                                     | Responsibility                                                                        | Use When                                                                                  |
|----------------------------------------------------------------------------|---------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| `foundation-core`                                                          | Application lifecycle, module system, roles, entity/singleton specs, service registry | Every Asteria application                                                                 |
| `foundation-actor`                                                         | Pekko actor base classes, actor-dispatcher coroutine scope, timer helpers             | Business actors need coroutines or lifecycle gates                                        |
| `foundation-ksp-support`                                                   | Shared KSP diagnostics and generator helpers                                          | Internal dependency of framework KSP modules; business code usually does not use it directly |
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

| Module                                                                                    | Responsibility                                                  | Use When                                                     |
|-------------------------------------------------------------------------------------------|-----------------------------------------------------------------|--------------------------------------------------------------|
| `cluster-pekko`                                                                           | Pekko actor system, cluster sharding, singletons, entity wakeup | The server runs on a Pekko cluster                           |
| `cluster-pekko-management` / `cluster-pekko-kubernetes`                                   | Pekko Management or Kubernetes discovery                        | Different deployment discovery strategies                    |
| `cluster-config`                                                                          | Reads node topology from a config center                        | Node host, port, roles, or seeds come from the config center |
| `gateway-core`                                                                            | Gateway transport, session, and dispatch abstractions           | The service accepts client connections                       |
| `gateway-netty`                                                                           | WebSocket/binary Netty transport                                | The gateway is exposed through Netty                         |
| `gateway-pekko`                                                                           | Adapter from gateway routes to Pekko runtime                    | Gateway packets must be forwarded to actors                  |
| `ephemeral-broadcast-core` / `ephemeral-broadcast-protobuf` / `ephemeral-broadcast-pekko` | Local or Pekko at-most-once broadcast plus protobuf payloads    | The service needs online, non-durable notifications          |
| `event-stream-core` / `event-stream-protobuf`                                             | Broker-neutral event contracts, outbox, and protobuf event codecs | Events need persistence, replay, or cross-system integration |
| `event-stream-nats-jetstream`                                                             | NATS JetStream durable event backend                           | JetStream carries internal business events                   |

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

| Module                                                                               | Responsibility                                                       | Use When                                          |
|--------------------------------------------------------------------------------------|----------------------------------------------------------------------|---------------------------------------------------|
| `script-core`                                                                        | Script engine, context, targets, execution results                   | GM or operational scripts are required            |
| `script-pekko`                                                                       | Maps script targets to Pekko nodes, roles, entities, and singletons  | Scripts need to run against actor runtime         |
| `script-job` / `script-job-mongodb`                                                  | Async script jobs, persisted results, throttling, leases             | GM scripts may be long-running or multi-target    |
| `gm-core`                                                                            | GM feature metadata, operation authorization, audit context          | The service has GM tools                          |
| `gm-shutdown`                                                                        | Business-side shutdown plans, phases, steps, and GM action metadata  | GM or operations workflows trigger graceful stop  |
| `gm-config-center`                                                                   | Read-only raw ConfigStore browsing, bounded previews, decoder hooks  | GM must inspect raw config center paths and bytes |
| `gm-*` starters                                                                      | Spring HTTP APIs and concrete feature adapters                       | GM operations are exposed over HTTP               |
| `ops-http-ktor`                                                                      | Node-local Ktor HTTP endpoint for SSH/curl script and patch controls | No GM node exists but local operations are needed |
| `patch-core` / `patch-jar` / `patch-mongodb` / `patch-config-center` / `patch-pekko` | Runtime patches, plugin resolution, repositories, cluster control    | Online runtime patches or patch audit are needed  |
| `observability-core` / `observability-opentelemetry`                                 | Metrics/tracing abstractions and OTel implementation                 | The service reports observability data            |
| `starter-game-server-pekko`                                                          | Local and cluster startup DSL, route module, patch starter           | Business projects want less startup glue          |

## How It Works

- `foundation-core`: runs module `install` and `start` in declaration order, then `stop` and `uninstall` in reverse
  order. `ServiceRegistry` uses exact type lookup; it does not search supertypes or interfaces.
- `foundation-contribution-*`: KSP scans `@AsteriaContribution` at compile time and emits static contribution lists.
  Runtime code does not scan the classpath. Business code turns the list into maps, grouped indexes, or patchable
  registries.
- `foundation-event-*`: KSP emits handler handles, registries, and dispatchers. Generated registries are patchable slot
  registries, so patches replace one handler slot rather than the dispatcher object.
- `foundation-message-*`: message KSP emits only handler handles. Application startup code chooses the concrete
  `MessageHandleRegistry` and constructs `MessageDispatcher`; `patch-core` provides the runtime patch registry when
  that behavior is desired.
- `config-*`: each `ConfigLoader` run creates a complete snapshot, which is published only after validators pass.
  Config-center watches are reread signals, not full state. Config KSP generates typed table access and change-handler
  lists.
- `cluster-pekko-*`: topology is declared by `foundation-core`; the Pekko runtime starts actor systems, sharding, and
  singletons from role, entity, and singleton metadata.
- `gateway-*`, `protocol-*`, and `rpc-*`: protocol registries map ids, types, and parsers; gateway transports manage
  connections and frames; route/forwarder code decides how messages reach actor runtime.
- `persistence-*`: actors own their data managers. Mongo KSP emits tracked wrappers and dirty-path plans; repositories
  flush dirty state in batches.
- `script-*`, `gm-*`, and `ops-http-ktor`: script runtime, GM features, and node-local HTTP control are separate layers.
  Script runtime owns targets and policy; GM/ops entry points own authorization, audit context, and request submission.
- `patch-*`: patch plugins declare service, message handler, or event handler replacements through
  `RuntimePatchInstallContext`. Base entries remain registered, so uninstall falls back to the next patch layer or the
  base implementation.

## Suggested Combinations

A minimal single-process service only needs `foundation-core` and business modules. A Pekko cluster service usually
combines `foundation-core`, `foundation-actor`, `cluster-pekko`, `config-core`, and one config-center backend. A public
gateway adds `gateway-core`, `gateway-netty`, `protocol-protobuf`, and the protocol codegen plugin. GM script support
adds `script-core`, `script-pekko`, `script-job`, a repository implementation, and `gm-script`. If no GM node exists but
operators can SSH to game machines, add `ops-http-ktor` for a loopback HTTP control endpoint protected by a bearer
token.
