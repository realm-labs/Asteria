# 应用生命周期

`foundation-core` 定义 Asteria 应用的最小运行模型：应用声明拓扑，模块注册服务，生命周期按顺序启动和反向停止。

## 最小应用

```kotlin
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.core.gameApplication

class GameClock {
    fun start() {
        // 业务自己的后台逻辑。
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

## 生命周期约定

`install` 用于创建和注册服务；`start` 执行依赖其他模块已安装完成后的启动动作；`stop` 做资源释放。模块按声明顺序安装和启动，停止时按反向顺序执行。

`AsteriaApplication` 是应用定义，不是某个节点的全部运行态。Pekko runtime 或业务 runtime 可以通过 `bind(runtime)`
复用同一套模块生命周期，把服务注册到自己的 `NodeRuntime.services`。

## 拓扑声明

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

`role`、`entity` 和 `singleton` 是运行时拓扑元数据。是否真的创建 actor system、启动 sharding 或 singleton，由
`cluster-pekko` 这类 runtime 模块完成。

## 服务注册

`ServiceRegistry` 是模块之间交换运行时对象的边界：

```kotlin
context.services.register(MyService::class, service)
val service = context.services.get(MyService::class)
val optional = context.services.find(MyOptionalService::class)
```

建议模块注册接口类型而不是具体实现，尤其是配置中心、持久化、GM、脚本和补丁这类可能有多种后端的服务。

## Actor 协程

`foundation-actor` 给 Pekko actor 提供 `AsteriaActor` 和 actor dispatcher 上的 coroutine scope。需要在协程中修改 actor
内部状态时，使用 actor 自己的 dispatcher，而不是全局 `Dispatchers.Default`。这样状态修改仍然落在 actor 的串行执行边界里。
