# Asteria

> 中文版请见 [README.zh.md](README.zh.md).

Asteria is a modular Kotlin game server runtime. It provides framework primitives for application modules, roles,
entities, message and event dispatch, Pekko cluster adapters, protocol registries, gateway sessions, persistence contracts,
configuration, scripts, GM tooling, runtime patches, and observability.

Game projects still own their topology and domain model. Concepts such as `World`, `Home`, `Player`, `Room`, or `Match`
are application-level choices, not framework requirements.

## Quick Start

Add only the modules your service needs:

```kotlin
dependencies {
    implementation("io.github.realm-labs.asteria:foundation-core:<version>")

  // Add feature modules as needed:
    implementation("io.github.realm-labs.asteria:config-core:<version>")
    implementation("io.github.realm-labs.asteria:cluster-pekko:<version>")
    implementation("io.github.realm-labs.asteria:gateway-netty:<version>")
}
```

Create an application, install modules, and launch it:

```kotlin
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.core.gameApplication

class GameRuntimeModule : AsteriaModule {
    override val name = "game-runtime"

    override suspend fun install(context: ModuleContext) {
        context.services.register(GameClock())
    }

    override suspend fun start(context: ModuleContext) {
        context.services.get<GameClock>().start()
    }
}

suspend fun main() {
    val app = gameApplication {
        name = "demo-game"
        role("player")
        install(GameRuntimeModule())
    }

    app.launch()
}
```

From there, add the runtime pieces the service needs:

- `ConfigModule` for config snapshots and hot reload.
- `PekkoRuntimeModule` for clustered actors, sharding, and singletons.
- `gateway-netty` for client transport.
- `persistence-*` modules for actor-local data.
- `script-*` and `gm-*` modules for operations tooling.

## Documentation

The detailed guides live under `docs/`:

- [English documentation](docs/en/README.md)
- [中文文档](docs/zh/README.md)

Start with:

- [Module map](docs/en/module-map.md)
- [Application lifecycle](docs/en/application-lifecycle.md)
- [Events](docs/en/events.md)
- [Config](docs/en/config.md)
- [Pekko cluster](docs/en/cluster-pekko.md)
- [Messaging, protocol, and gateway](docs/en/messaging-protocol-gateway.md)
- [Persistence](docs/en/persistence.md)
- [Script and GM](docs/en/script-and-gm.md)
- [Runtime patches](docs/en/patch.md)
- [Observability and starter](docs/en/observability-and-starter.md)

## Example Project

- [Antares](https://github.com/mikai233/antares): a real game-server scaffold that demonstrates how an Asteria-based
  service can be organized across gateway, world, player, GM, configuration, protocol, and tooling modules.

## Runtime Contracts

- Startup topology, such as node host, port, role, and seed entries, is process startup input. It is not
  hot-reconfigured into an already running Pekko actor system.
- Startup failure is fatal. `launch()` rethrows the original startup error after best-effort rollback of modules that
  already installed or started; applications should usually let the process exit.
- Config tables are loaded once by default. Enable `ConfigModule.hotReload` only when the game explicitly supports
  reloading those tables. Hot reload publishes complete validated snapshots.
- Config-center watches are notification sources. Prefer `RuntimeConfigRepository` and `ConfigCenterReloadTrigger` for
  long-running services because they rebuild failed watches and reread state.
- Lease-backed services fail closed. Worker IDs and script-job permits retry transient backend failures only while the
  current lease is still valid.

## Publishing

Local verification:

```bash
./gradlew publishToMavenLocal
```

Maven Central releases are handled by manually running `.github/workflows/release.yml` from GitHub Actions. The workflow
bumps or accepts a release version, creates the release tag, rejects duplicate versions, and publishes artifacts under
`io.github.realm-labs.asteria`.

Required repository secrets:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_IN_MEMORY_KEY`
- `SIGNING_IN_MEMORY_KEY_PASSWORD`
- `RELEASE_GITHUB_TOKEN`

Before the first release, verify the `io.github.realm-labs` namespace in Central Portal and distribute the signing
public key.
