# 观测和启动器

## Observability

`observability-core` 提供框架内部使用的 metrics 和 tracing 抽象。模块通过 `context.metricsOrNoop()` 和
`context.tracerOrNoop()` 获取实现；没有安装观测模块时自动退化为 noop。

```kotlin
install(ObservabilityModule {
    observability(OpenTelemetryObservability(openTelemetry))
})
```

`observability-opentelemetry` 提供 OpenTelemetry adapter。业务侧负责创建和配置 SDK、exporter、resource attributes 和采样策略。

指标名称和 tag 要保持低基数。`NoopMetrics` 会校验指标名和 tag key，但不会上报数据；不要因为本地 noop 可运行就忽略生产
exporter 的 cardinality 成本。

`ObservabilityModule` 只在 `install` 阶段向 `ServiceRegistry` 注册 `Observability`、`Tracer` 和 `Metrics`。它不负责启动
OpenTelemetry SDK，也不拥有 exporter 生命周期。框架模块只依赖 `Tracer`/`Metrics` 抽象；没有安装模块时，
`metricsOrNoop()` 和 `tracerOrNoop()` 返回 noop 实现。

## Starter

`starter-game-server-pekko` 是业务启动胶水层，适合快速搭建本地或集群服务：

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

`localGameApplication` 适合本地开发和测试；它先安装 `PekkoRuntimeModule(LocalPekkoClusterStartup())`，再执行业务
configure，最后安装启动摘要模块。`clusterGameApplication` 用于 Pekko 集群运行时，会安装配置中心/拓扑 provider、
`ClusterConfigModule`、`PekkoRuntimeModule(TopologyPekkoClusterStartup(...))` 和启动摘要模块。`LocalGameCluster`
可以在同一 JVM 中启动多个应用实例，适合集成测试和调试拓扑。

本地多节点默认把第一个 node 当作 seed，除非显式声明 seed。`publishClusterTopology()`
只发布当前节点记录，不负责清理配置中心里的旧节点；测试或本地开发重复运行时要注意旧拓扑残留。

starter 只组合常用模块和默认顺序，不替代生产 wiring。需要自定义 discovery、配置中心、观测
SDK、gateway、补丁仓库或持久化后端时，应显式安装对应模块或使用更底层的 `gameApplication`。

## RouteModule

`RouteModule` 把业务 route registry 注册进服务容器。它只描述路由目标，不负责网络协议解码、权限、鉴权或 actor 业务处理。

## Patch starter

`runtimePatches { ... }` 是 `PatchModule` 的启动器语法糖。需要补丁仓库、artifact store 或集群控制时，仍然应该按生产环境显式配置对应实现。
starter 默认启用节点本地周期补丁 reconcile；设置 `reconcileInterval = null` 可以关闭后台 desired-state 检查。

## Utils Game

`utils-game` 提供游戏常用的小工具：`Rate`、`WeightedTable`、`Fraction`、`GameTimeRange`、`GameDayRule` 和 `Cooldown`
。这些工具有明确边界，例如 `Rate.percent(33).applyTo(10)` 会做整数截断，`GameTimeRange` 是半开区间 `[start, end)`
，跨区服时间规则应显式传 `ZoneId`。
