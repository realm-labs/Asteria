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

`install` 用于创建和注册服务；`start` 执行依赖其他模块已安装完成后的启动动作；`stop` 停止运行时工作；`uninstall` 释放
install 阶段分配的资源。模块按声明顺序安装和启动，停止和卸载时按反向顺序执行。

`AsteriaApplication` 是应用定义，不是某个节点的全部运行态。Pekko runtime 或业务 runtime 可以通过 `bind(runtime)`
复用同一套模块生命周期，把服务注册到自己的 `NodeRuntime.services`。

内部由 `AsteriaModuleLifecycle` 驱动。它为每次 launch 构造 `ModuleContext`，把 `AsteriaModuleLifecycle` 自身注册进
`ServiceRegistry`，再依次执行所有模块的 `install` 和 `start`。生命周期状态通过 `NodeState` 暴露；`onState`
监听器在状态切换时同步执行，适合做轻量通知，不适合递归启动/停止同一个 lifecycle。

启动失败对当前启动尝试来说是致命错误。任意模块在 `install` 或 `start` 阶段失败时，`launch()` 会停止已经
`start` 成功的模块，卸载已经 `install` 成功的模块，把清理失败作为 suppressed exception
挂到原始异常上，最后重新抛出原始启动异常。生产入口通常应该让这个异常终止进程。

如果宿主运行时有自己的关闭流程，可以使用 `stopAfter(moduleName)` 停止声明在某个模块之后的模块。Pekko runtime
会把它接入 `CoordinatedShutdown`，避免 ActorSystem 关闭时又递归终止自己。

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

`role`、`entity` 和 `singleton` 是运行时拓扑元数据。`role` 声明应用可能使用的角色；`entity` 记录 kind、id 类型、
目标 role、shard 数、message extractor、actor props 和启动方式；`singleton` 记录名称、承载 role、actor props 和启动方式。是否真的创建
actor system、启动 sharding 或 singleton，由 `cluster-pekko` 这类 runtime 模块完成。

`@AsteriaDsl` 只用于限制 DSL receiver，避免在嵌套 builder 中误调用外层方法；它不是运行时注解，也不会生成代码。

## 服务注册

`ServiceRegistry` 是模块之间交换运行时对象的边界：

```kotlin
context.services.register(MyService::class, service)
val service = context.services.get(MyService::class)
val optional = context.services.find(MyOptionalService::class)
```

注册和查询按精确 `KClass` 匹配，不会自动按接口或父类查找。重复注册同一个 key
会覆盖旧实例。建议模块注册接口类型而不是具体实现，尤其是配置中心、持久化、GM、脚本和补丁这类可能有多种后端的服务。

`ServiceRegistry` 有意保持轻量，不负责并发同步。大多数服务应在 `install` 阶段完成注册；如果运行中会替换服务，需要业务自己提供更高层的同步和可见性约束。

## Actor 协程

`foundation-actor` 给 Pekko actor 提供 `AsteriaActor` 和 actor dispatcher 上的 coroutine scope。需要在协程中修改 actor
内部状态时，使用 actor 自己的 dispatcher，而不是全局 `Dispatchers.Default`。这样状态修改仍然落在 actor 的串行执行边界里。
