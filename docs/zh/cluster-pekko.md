# Pekko 集群

`cluster-pekko` 把 `foundation-core` 中声明的角色、实体和单例落到 Pekko actor system、cluster sharding 和 cluster
singleton。

## 启动 runtime

```kotlin
val app = gameApplication {
    name = "game"

    role("world")

    entity<Long>("world") {
        role = RoleKey("world")
    }

    install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
}

app.launch()
```

业务项目通常会在 `cluster-config` 中从配置中心读取当前节点的 host、port、roles 和 seed nodes，再交给 Pekko
runtime。启动拓扑属于进程启动输入，变更后通常通过滚动重启生效。

`PekkoRuntimeModule` 的 `install` 阶段调用 `PekkoClusterStartup.resolve()` 得到启动计划，创建 `ActorSystem`，根据计划更新当前节点
roles，并注册 `PekkoRuntime`、`ActorSystem`、`RuntimeNodeConfig`、`ClusterTopology`、`EntityShardRegistry` 和
`SingletonActorRegistry`。`start` 阶段才读取应用声明的 entity/singleton 拓扑并启动 sharding、singleton 或 proxy。

`TopologyPekkoClusterStartup` 会从 `ClusterTopologyProvider.current()` 取完整拓扑，用 `nodeId` 选出当前节点，校验应用声明的
roles 都被拓扑覆盖，然后由 `PekkoClusterConfig.build()` 生成 Pekko host、port、role 和 seed-node 配置。`watch()`
可供工具展示变化，默认 Pekko 启动路径不会把拓扑热应用到已经创建的 ActorSystem。

## Entity 和 sharding

实体声明中的 `kind` 必须和业务消息 extractor、实体 actor 注册保持一致。发送给 sharding 的消息需要能被 extractor 拿到
entity id。

```kotlin
data class WorldWakeupReq(val worldId: Long) : Serializable

val extractor = PekkoShardExtractors.longId<WorldWakeupReq>(
    entityId = { it.worldId },
)
```

具体 actor props、message extractor 和启动 wiring 由业务模块接入；框架提供通用拓扑和辅助类型。

启动方式决定当前节点拿到的引用类型：

- `Auto`：节点拥有 entity role 时启动 shard region，否则启动 proxy。
- `Region`：必须拥有 entity role，否则启动失败。
- `Proxy`：总是启动 proxy。

如果 entity 没有声明 extractor，runtime 使用按 entity id hash 的默认 extractor。启动 region 时必须提供 actor props；启动
proxy
只需要 kind、role 和 extractor。启动后引用注册到 `EntityShardRegistry`，下游转发模块可以按 kind 取用。

Singleton 的 `Auto` 只在拥有目标 role 的节点启动 host，`Host` 会强制校验 role，`Proxy` 不启动 host。无论当前节点是否 host，
runtime 都会启动 singleton proxy 并注册到 `SingletonActorRegistry`。

## 实体预唤醒

`PekkoEntityWakerModule` 适合在集群启动后分批唤醒常驻实体，例如 World actor。它会从 source 读取完整目标集合，对尚未完成的目标发
ask 消息，并根据成功率动态调整并发。

```kotlin
install(PekkoEntityWakerModule {
    task<Long>("world") {
        kind("world")

        readiness = PekkoEntityWakeReadiness(
            role = RoleKey("world"),
            minUpRatio = 0.8,
        )

        concurrency = PekkoEntityWakeConcurrency(
            initial = 20,
            min = 5,
            max = 80,
        )

        retry = PekkoEntityWakeRetry(
            maxAttempts = 10,
            exhaustedDelay = 10.minutes,
        )

        targets {
            services.get<WorldConfigService>().worldIds()
        }

        message { worldId -> WorldWakeupReq(worldId) }

        success { response ->
            response is WorldWakeupResp && response.ok
        }
    }
})
```

source 要返回当前完整目标集，不是 delta。配置热更后 coordinator 会 reconcile，新增目标会进入唤醒队列，已完成目标不会重复排队。

## GM 控制

实体 waker 暴露控制服务，供 GM 或运维工具查询和控制：

```kotlin
val waker = services.get<PekkoEntityWaker>()

val status = waker.status("world")
waker.wake("world", listOf("1001", "1002"))
waker.cancel("world", listOf("1003"), reason = "bad world data")
waker.reconcile("world")
```

`status` 用于查看 pending、in-flight、completed、failed/exhausted 等状态。对一直失败的坏 actor，应先取消目标，修复数据后再手动
wake 或等待 source 重新 reconcile。

## 失败和重试

默认重试不是永久短周期打满。每个目标达到 `maxAttempts` 后会进入 exhausted；如果配置了 `exhaustedDelay`，后续会低频重试。坏数据或代码
bug 导致的固定失败，需要 GM cancel，否则低频重试仍会持续产生噪音。

唤醒消息必须幂等。coordinator 重启、配置 source 变化、GM 手动 wake 都可能让同一个 entity id 再次收到 wake 消息。

## 序列化

跨节点控制消息和状态响应不能依赖 Java 默认序列化。`cluster-pekko` 为 entity waker 控制消息提供显式
serializer；新增对外控制消息时也需要同步 serializer 和 `reference.conf`。
