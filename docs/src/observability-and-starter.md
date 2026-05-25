# Observability and Starter

## Observability

`observability-core` provides the metrics and tracing abstractions used by framework modules. Modules read
implementations through `context.metricsOrNoop()` and `context.tracerOrNoop()`; if no observability module is installed,
the runtime falls back to noop implementations.

```kotlin
install(ObservabilityModule {
    observability(OpenTelemetryObservability(openTelemetry))
})
```

`observability-opentelemetry` provides an OpenTelemetry adapter. Business code owns SDK setup, exporters, resource
attributes, and sampling policy.

Metric names and tags should stay low-cardinality. `NoopMetrics` validates metric names and tag keys but does not report
data; do not ignore production exporter cardinality costs just because local noop runs successfully.

`ObservabilityModule` only registers `Observability`, `Tracer`, and `Metrics` in the `ServiceRegistry` during `install`.
It does not start the OpenTelemetry SDK and does not own exporter lifecycle. Framework modules depend only on the
`Tracer`/`Metrics` abstractions; without an installed module, `metricsOrNoop()` and `tracerOrNoop()` return noop
implementations.

## Starter

`starter-game-server-pekko` is startup glue for game servers:

```kotlin
suspend fun main() {
    val app = clusterGameApplication {
        name = "game"
        nodeId = "world-1"

        role("world")

        routes {
            route<EnterWorldReq> {
                toEntity("world") { it.id }
            }
        }

        install(GameRuntimeModule())
    }

    app.launch()
}
```

`localGameApplication` is for local development and tests; it installs
`PekkoRuntimeModule(LocalPekkoClusterStartup())`, runs business configuration, then installs the startup-summary module.
`clusterGameApplication` is for Pekko cluster runtime; it installs config-center/topology provider wiring,
`ClusterConfigModule`, `PekkoRuntimeModule(TopologyPekkoClusterStartup(...))`, and the startup-summary module.
`LocalGameCluster` can start multiple application instances in one JVM for integration tests and topology debugging.

Local multi-node startup uses the first node as the seed by default unless seeds are declared explicitly.
`publishClusterTopology()` only writes current node records; it does not clean old nodes from the config center, so
repeated local runs should account for stale topology.

Starter only composes common modules and default ordering; it is not a replacement for production wiring. If deployment
needs custom discovery, config-center setup, observability SDK setup, gateway modules, patch repositories, or
persistence
backends, install those modules explicitly or use lower-level `gameApplication`.

## RouteModule

`RouteModule` registers the business route registry into the service container. It only describes route targets; it does
not handle network protocol decoding, authentication, authorization, or actor business logic.

## Patch Starter

`runtimePatches { ... }` is a convenience DSL for `PatchModule`. Production deployments should still explicitly
configure repositories, artifact stores, and cluster-control implementations. The starter enables node-local periodic
patch reconciliation by default; set `reconcileInterval = null` to disable the background desired-state check.
For Pekko nodes, `runtimePatches(version = BuildInfo.version, ...)` derives the patch environment from the current
application and actor system; use the `PatchEnvironment` overload only when the host process supplies those values
itself.

## Utils Game

`utils-game` contains small game-oriented helpers such as `Rate`, `WeightedTable`, `Fraction`, `GameTimeRange`,
`GameDayRule`, and `Cooldown`. These helpers have explicit boundaries: `Rate.percent(33).applyTo(10)` truncates integer
output, `GameTimeRange` is a half-open interval `[start, end)`, and cross-region time rules should pass an explicit
`ZoneId`.
