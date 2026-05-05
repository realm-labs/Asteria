# Application Lifecycle

`foundation-core` defines the smallest Asteria runtime model: applications declare topology, modules register services,
and lifecycle methods run in declaration order and stop in reverse order.

## Minimal Application

```kotlin
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.core.gameApplication

class GameClock {
    fun start() {
        // Start game-local background work.
    }
}

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
        role("world")
        install(GameRuntimeModule())
    }

    app.launch()
}
```

## Lifecycle Contract

Use `install` to create and register services. Use `start` for runtime work that depends on other modules already being
installed. Use `stop` to stop runtime work and `uninstall` to release resources allocated during install. Modules are
installed and started in declaration order, then stopped and uninstalled in reverse order.

`AsteriaApplication` is an application definition, not the full runtime state of a node. Pekko or business runtimes can
call `bind(runtime)` to reuse the same module lifecycle while storing services in their own `NodeRuntime.services`.

Startup failure is fatal to the launch attempt. If any module fails during `install` or `start`, `launch()` stops modules
whose `start` completed, uninstalls modules whose `install` completed, attaches cleanup failures as suppressed
exceptions, and rethrows the original startup error. Production entry points should usually let that error terminate the
process.

## Topology Declaration

```kotlin
val app = gameApplication {
    name = "game"

    val worldRole = role("world")

    entity<Long>("world") {
        role = worldRole
    }

    singleton("rank") {
        role = worldRole
    }
}
```

`role`, `entity`, and `singleton` are topology metadata. Creating an actor system, starting sharding, and hosting
singletons is done by runtime modules such as `cluster-pekko`.

## Service Registry

`ServiceRegistry` is the module-to-module runtime boundary:

```kotlin
context.services.register(MyService::class, service)
val service = context.services.get(MyService::class)
val optional = context.services.find(MyOptionalService::class)
```

Prefer registering interface types for services that may have multiple implementations, such as config centers,
persistence, GM, scripts, and patches.

## Actor Coroutines

`foundation-actor` provides `AsteriaActor` and a coroutine scope that runs on the actor dispatcher. Use that scope when
a coroutine mutates actor state; the mutation still stays inside the actor's serialized execution boundary instead of
running on `Dispatchers.Default`.
