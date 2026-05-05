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

## Documentation Conventions

Examples show the intended framework integration style, not a complete game implementation. When business code must
provide a decision, the guide calls that out explicitly, such as authentication, player binding, script approval,
config-event delivery, and Mongo client lifecycle.
