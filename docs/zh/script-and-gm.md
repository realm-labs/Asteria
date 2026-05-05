# 脚本和 GM

脚本模块用于运维脚本、GM 修复和批量诊断。GM 模块负责 feature 元数据、权限、审计上下文和 HTTP starter。两者可以独立使用，也可以组合成完整后台能力。

## 脚本运行时

`script-core` 定义脚本资源、上下文、策略、目标和执行结果。具体引擎由 `script-engine-groovy` 或 `script-engine-jar` 提供。

```kotlin
val command = ScriptExecutionCommand(
    executionId = "gm-20260505-001",
    target = ScriptTarget.Entity(kind = "world", ids = listOf("1001")),
    resource = ScriptResource.Inline(
        name = "fix-world",
        language = "groovy",
        content = "world.rebuildIndex()",
    ),
    metadata = ScriptExecutionMetadata(requester = "gm:alice"),
)

val result = scriptRuntime.execute(command)
```

脚本策略应该由项目按风险定义，例如允许的语言、超时、目标范围、审批状态和可调用函数。

## Pekko 脚本目标

`script-pekko` 把脚本目标映射到 Pekko runtime：

- `AllNodes`：每个节点执行。
- `Role`：指定角色节点执行。
- `Node`：指定节点执行。
- `ActorPath`：指定 actor path 执行。
- `Entity`：指定 sharding entity 执行。
- `Singleton`：指定 cluster singleton 执行。

业务 actor 需要实现脚本执行入口，或者继承/组合 `ScriptableAsteriaActor` 相关支持。

`ScriptModule` 的 `allowNodeScripts` 和 `allowActorScripts` 默认都是 `false`。没有显式开启时，对应目标会被默认策略拒绝。目标
actor 没有接入 `ActorScriptSupport` 时，actor path、entity 和 singleton 目标也不会执行脚本。

## 异步脚本任务

`script-job` 负责把多目标脚本展开成 item，持久化状态，后台执行，并提供 retry/cancel/export：

```kotlin
val job = scriptJobService.submit(command, timeout = 3.seconds)

val page = scriptJobService.listItems(job.id)
val summary = scriptJobService.summarizeResults(job.id)

scriptJobService.retryFailedItems(job.id, requestedBy = "gm:alice")
scriptJobService.cancelJob(job.id, ScriptJobCancellation(requestedBy = "gm:alice"))
```

`ScriptJobService.submit` 返回的是“任务状态已写入”，不是“脚本已经执行完成”。结果需要通过查询接口读取。

`timeout` 是单个 item attempt 的超时，不是整个 job 的总 deadline。`executionId` 和 target
会影响幂等与重放语义，重复提交同一个命令不应该被业务理解成一定重新执行。

## 限流和租约

`ScriptJobExecutionLimiter` 控制并发和外部 permit。Mongo permit repository 用租约避免多个后台 worker 同时执行同一个
item。外部服务短暂不可用时会在租约有效期内重试；一旦无法证明持有 permit，执行会 fail closed。

如果业务希望完全不限流，可以显式使用 noop limiter，但生产环境通常应保留限流和 permit。

## GM feature

GM feature 是后台能力的元数据：

```kotlin
class RechargeFeature : GmFeature {
    override val descriptor = GmFeatureDescriptor(
        id = GmFeatureId("recharge"),
        name = "Recharge",
        permissions = listOf(
            GmPermission(GmPermissionKey("recharge.grant"), "Grant Recharge", highRisk = true),
        ),
        menus = listOf(
            GmMenuItem(id = "recharge", title = "Recharge", route = "/recharge"),
        ),
    )
}
```

`GmFeatureRegistry` 会拒绝重复 feature id 和重复 permission key。可选模块通过 Java `ServiceLoader` 发布 feature，Spring
starter 暴露 feature 列表和具体 HTTP API。

`highRisk` 只是元数据标记，不会自动触发审批、MFA 或工单流。生产系统要在 `GmPrincipalResolver`、`GmPolicyEvaluator` 或业务
controller 中补上这些策略。

## Spring starter

- `gm-spring-boot-starter`：feature 元数据 API、principal、异常处理。
- `gm-script-spring-boot-starter`：脚本提交、查询、重试、取消等 HTTP API。
- `gm-config-spring-boot-starter`：配置快照查询和集群配置控制。
- `gm-cluster-spring-boot-starter`：集群状态和 actor 查询。
- `gm-patch-spring-boot-starter`：补丁管理。

安全边界由业务 Spring 应用接入。框架提供 permission key 和审计模型，不替业务决定登录、审批、MFA 或工单流程。

默认 noop principal resolver 不会解析用户，GM HTTP 接口会认证失败。接入 starter 时，业务至少需要提供自己的
`GmPrincipalResolver`，通常还要提供 `GmAuditSink` 和符合项目权限模型的 policy evaluator。
