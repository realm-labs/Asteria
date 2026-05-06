# Asteria Documentation

This directory groups documentation by the capabilities developers need to use. The root `README.md` stays as the
project entry point and module list; detailed module responsibilities, usage examples, and runtime contracts live here.

## Reading Path

- [Module Map](module-map.md): decide which modules a project needs and which modules are adapters.
- [Application Lifecycle](application-lifecycle.md): `gameApplication`, module order, service registration, and actor
  utilities.
- [Events](events.md): fact events, topic trees, ancestor fan-out, and event handler registration.
- [Config](config.md): config snapshots, hot reload, config centers, Luban, code generation, and config change dispatch.
- [Pekko Cluster](cluster-pekko.md): cluster startup, entity/singleton declarations, entity wakeup, and config-center
  topology.
- [Messaging, Protocol, and Gateway](messaging-protocol-gateway.md): business messages, handler registration, protobuf
  protocol generation, gateway sessions, and Netty transport.
- [Persistence](persistence.md): actor-local data management, Mongo tracked wrappers, and KSP conventions.
- [Script and GM](script-and-gm.md): script runtime, async script jobs, GM features, and Spring HTTP starters.
- [Runtime Patches](patch.md): patch repositories, plugin resolution, single-node and cluster patch application.
- [Observability and Starter](observability-and-starter.md): metrics/tracing abstractions, OpenTelemetry adapter, and
  local/cluster startup DSLs.

## Module to Guide Mapping

- `foundation-*`: start with [Application Lifecycle](application-lifecycle.md);
  read [Events](events.md) for domain events and
  [Messaging, Protocol, and Gateway](messaging-protocol-gateway.md) for message dispatch.
- `cluster-*`: read [Pekko Cluster](cluster-pekko.md); read [Config](config.md) for config-center topology.
- `config-*`: read [Config](config.md).
- `gateway-*`, `protocol-*`, `rpc-*`, `broadcast-*`:
  read [Messaging, Protocol, and Gateway](messaging-protocol-gateway.md).
- `persistence-*`: read [Persistence](persistence.md).
- `script-*`, `gm-*`: read [Script and GM](script-and-gm.md).
- `patch-*`: read [Runtime Patches](patch.md).
- `observability-*`, `starter-*`, `utils-game`: read [Observability and Starter](observability-and-starter.md).

## Example Project

- [Antares](https://github.com/mikai233/antares): a real game-server scaffold that shows how an Asteria-based server can
  be split into gateway, world, player, GM, configuration, protocol, and tooling areas.

## KSP Snapshots

Asteria KSP modules also generate JSON snapshots that describe the source model seen by code generation. These snapshots
are intended for optional project-side verification, not runtime configuration.

- `META-INF/asteria/codegen-snapshots/message/<moduleId>.json`
- `META-INF/asteria/codegen-snapshots/event/<rootPackage>.json`, with non-alphanumeric root package characters replaced by `_`
- `META-INF/asteria/codegen-snapshots/config/config.json`
- `META-INF/asteria/codegen-snapshots/persistence-mongodb/entities.json`

A project can sync these snapshots into version control and compare them after regeneration in CI or release builds to
catch unexpected handler, topic, config table, or Mongo wrapper model changes.

## Documentation Conventions

Examples show the intended framework integration style, not a complete game implementation. When business code must
provide a decision, the guide calls that out explicitly, such as authentication, player binding, script approval,
config-event delivery, and Mongo client lifecycle.
