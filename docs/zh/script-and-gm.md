# 脚本和 GM

脚本模块用于运维脚本、GM 修复和批量诊断。GM 模块负责 feature 元数据、权限、审计上下文和 HTTP starter。两者可以独立使用，也可以组合成完整后台能力。

## 脚本运行时

`script-core` 定义脚本资源、上下文、策略、目标和执行结果。具体引擎由 `script-engine-groovy` 或 `script-engine-jar` 提供。
核心调用链是：

1. 入口构造 `ScriptExecutionCommand`，其中 `executionId` 是幂等和审计 key，`target` 描述路由目标，`artifact` 携带脚本内容和引擎名，
   `metadata` 携带操作者、原因、审批、权限、工单和资源引用。
2. `ScriptRuntime` 根据目标路由命令。`execute` 只适合单一有效结果，`executeAll` 会收集超时前可观察到的所有结果，`dispatch`
   是 fire-and-forget。
3. 目标节点或 actor 把命令转换为 `ScriptExecutionRequest`，补上 `scope`、节点地址或 actor path。
4. `ScriptRunner` 先调用 `ScriptPolicy.authorize`，拒绝时写审计并返回失败结果；允许时通过 `ScriptExecutionStore`
   做运行中/已完成判断，再调用 `ScriptExecutor`。
5. `ScriptExecutor` 从 `ScriptEngineRegistry` 找到引擎，按 `artifact.engine` 编译并执行。脚本拿到的 `ScriptContext` 包含
   runtime、services、metadata、资源读取和可选取消 token。

```kotlin
val command = ScriptExecutionCommand(
    executionId = "gm-20260505-001",
  target = ScriptTarget.Entity(kind = EntityKind("world"), ids = listOf("1001")),
  artifact = ScriptArtifact(
        name = "fix-world",
    engine = "groovy",
    body = "world.rebuildIndex()".encodeToByteArray(),
    ),
    metadata = ScriptExecutionMetadata(requester = "gm:alice"),
)

val result = scriptRuntime.execute(command)
```

`ScriptPolicy` 是执行前的硬边界。默认策略可以限制 node/actor scope、允许的 engine、允许的 target type、artifact
大小、审批、签名、模板、engine/target 权限和明显危险的 API token。默认权限检查只读
`metadata.attributes["script.permissions"]`，生产系统通常应接入自己的 permission authorizer、signature verifier、template
catalog 和 audit sink。

`ScriptTarget` 只描述“脚本要去哪里”，不表达权限。当前目标包括 `AllNodes`、`Role`、`Node`、`ActorPath`、`Entity` 和
`Singleton`；具体能否路由、是否 fan-out、是否需要 actor 配合由 runtime 实现决定。

## Groovy 和 jar artifact

`ScriptArtifact` 的 `engine` 必须匹配已注册的脚本引擎。Groovy 引擎把 artifact body 当作 UTF-8 Groovy 源码编译，可以是顶层脚本，
也可以是实现 `BlockingScriptFunction`、`CompiledScript`、`NodeScript` 或 `ActorScript` 的脚本类。jar 引擎把 body 当作
JAR 字节读取，JAR manifest 必须包含 `Script-Class`，指向可被 `toCompiledScript` 适配的类。两个引擎都会按 checksum（有则优先）或
body 内容生成缓存 key。

直接发送 Groovy 文本时，顶层脚本会拿到以下 binding：`context`、`runtime`、`services`、`request`、`artifact`、`metadata`、
`resources`、`tables` 和 `cancellation`。node 上下文额外提供 `target` 和 `nodeAddress`；actor 上下文额外提供 `actor`、
`target` 和 `actorPath`。脚本返回 `ScriptExecutionResult` 时使用该结果；返回 `null` 或其它值时，runner 会生成默认成功结果。

```groovy
// fix-player.groovy
actor.repairPlayer("1001")
```

## 大文件资源

大文件不要放进 `bodyText`、`bodyBase64` 或 multipart artifact。脚本入口只携带资源引用，真正文件放在本地路径、共享目录、HTTP
地址或对象存储中：

```json
{
  "metadata": {
    "resources": [
      {
        "name": "compensation",
        "uri": "s3://ops/compensation.csv",
        "checksum": "sha256:<hex>",
        "format": "csv",
        "attributes": {
          "downloadUrl": "https://..."
        }
      }
    ]
  }
}
```

默认资源解析器只支持节点本地路径和 `file:` URI。需要让脚本读取 HTTP 或对象存储资源时，在 `ScriptModule` 中显式配置：

```kotlin
install(ScriptModule {
    engine(GroovyScriptEngine())
    allowNodeScripts = true
    allowActorScripts = true
    resourceCache(Path.of("/var/lib/asteria/script-resources"))
})
```

`resourceCache` 会注册 `CachingScriptResourceResolver`，远程资源首次读取时下载到节点本地缓存，并按 checksum
校验。需要接入业务自己的存储、鉴权或下载逻辑时，使用
`resourceResolver(customResolver)`。

上传 groovy 或 jar 只是在入口层把文件转换成 `ScriptArtifact`，不会绕过策略。文件大小还会受入口配置和
`ScriptPolicy.maxArtifactBytes`
共同限制；checksum、签名、模板 id、审批人、权限、工单号等都应放在 metadata attributes 中，由业务策略验证。

脚本与 jar class 会在服务进程内执行，能通过 `ScriptContext.services` 调用业务注册服务。因此安全边界应放在提交入口、
`ScriptPolicy`、代码评审/签名、审计和运行账号权限上；不要把 Groovy/JAR 执行理解成隔离沙箱。

## Pekko 脚本目标

`script-pekko` 把脚本目标映射到 Pekko runtime：

- `AllNodes`：每个节点执行。
- `Role`：指定角色节点执行。
- `Node`：指定节点执行。
- `ActorPath`：指定 actor path 执行。
- `Entity`：指定 sharding entity 执行。
- `Singleton`：指定 cluster singleton 执行。

`PekkoScriptRuntime` 后面是本地 `ScriptRuntimeActor`。`AllNodes` 和 `Role` 会先在本节点执行，再通过 distributed pub-sub
发布到其他节点；
`Node` 只在地址匹配的节点执行；`ActorPath` 用 actor selection 逐个发送；`Entity` 通过 `EntityShardRegistry` 转到 sharding
entity；
`Singleton` 通过 `SingletonActorRegistry` 发送。`executeAll` 会创建临时 collector，在 timeout
前收集返回结果；目标不存在或响应太慢时可能得到部分结果或空结果。

业务 actor 需要实现脚本执行入口，通常是在 actor 内组合 `ActorScriptSupport`，并把 `ActorScriptSupport.receive()` 合并进允许执行脚本的 receive 状态。

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

提交时会先创建 `ScriptJob`，再把 target 展开成 item：`Node` 按地址拆分，`ActorPath` 按 path 拆分，`Entity` 按 id 拆分；
`AllNodes`、`Role`、`Singleton` 保持为一个 item，让 runtime 自己 fan-out。每个 item attempt 的 execution id 会派生为
`sourceExecutionId.itemId.attempt`，并在 metadata 中追加 `script.jobId`、`script.itemId`、`script.attempt`、
`script.workerId` 和
`script.sourceExecutionId`。

item 状态通常是 `Pending -> Running -> Completed/Failed/Cancelled`。取消 pending item 会直接落库；取消 running item
依赖脚本协作取消或租约恢复路径把它变为终态。retry 只允许 failed item，会新建一次 attempt 并重新调度。`summarizeResults`
按错误聚合失败样本，`exportResults` 导出 item 级 CSV。

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

`GmFeatureRegistry` 是不可变 feature catalog，会在启动时拒绝重复 feature id 和重复 permission key，避免扩展模块静默覆盖彼此。
feature descriptor 只保存元数据：feature id/name/description、permission、menu 和 route。可选模块通过 Java `ServiceLoader`
发布 feature；`gm-spring-boot-starter` 会把 ServiceLoader 发现的 feature 和 Spring bean 中的 `GmFeature` 合并后注册，并暴露
feature、permission、menu、route 列表。具体业务 API 仍由各 starter 或业务 controller 提供。

`highRisk` 只是元数据标记，不会自动触发审批、MFA 或工单流。生产系统要在 `GmPrincipalResolver`、`GmPolicyEvaluator` 或业务
controller 中补上这些策略。

## 停服编排

`gm-shutdown` 提供业务侧 graceful shutdown 的通用编排框架：plan 按 phase 顺序执行，每个 phase 内按 step 顺序执行。框架只记录
状态、超时、失败和 GM 权限元数据，不绑定 PlayerActor、WorldActor、网关或进程退出语义。

```kotlin
install(gmShutdownModule {
    phase("gateway-drain") {
        step("stop-accepting") { context ->
            context.services.get<GatewayControl>().stopAccepting()
            GmShutdownStepResult.succeeded()
        }
        step("close-sessions") { context ->
            context.services.get<GatewayControl>().closeAllSessions()
            GmShutdownStepResult.succeeded()
        }
    }

    phase("player-drain") {
        step("flush-players") { context ->
            context.services.get<PlayerDrainService>().drainOnlinePlayers()
            GmShutdownStepResult.succeeded()
        }
    }

    phase("world-drain") {
        step("flush-worlds") { context ->
            context.services.get<WorldDrainService>().drainWorlds()
            GmShutdownStepResult.succeeded()
        }
    }
})
```

GM 或运维入口可以从 `ServiceRegistry` 取出 `GmShutdownOperations`，提交 `GmShutdownRequest` 并查询 `status()`。完成业务 drain
后，最后的 step 可以调用业务提供的节点退出服务，或者只把节点标记为 `ready-to-exit`，交给部署系统缩容或发 SIGTERM。

## Spring starter

- `gm-spring-boot-starter`：feature 元数据 API、principal、异常处理。
- `gm-script-spring-boot-starter`：脚本提交、查询、重试、取消等 HTTP API。
- `gm-config-spring-boot-starter`：配置快照查询和集群配置控制。
- `gm-cluster-spring-boot-starter`：集群状态和 actor 查询。
- `gm-patch-spring-boot-starter`：补丁管理。

安全边界由业务 Spring 应用接入。框架提供 permission key 和审计模型，不替业务决定登录、审批、MFA 或工单流程。

默认 noop principal resolver 不会解析用户，GM HTTP 接口会认证失败。接入 starter 时，业务至少需要提供自己的
`GmPrincipalResolver`，通常还要提供 `GmAuditSink` 和符合项目权限模型的 policy evaluator。

## 节点本地 Ops HTTP

`ops-http-ktor` 提供一个独立于 GM 的节点本地 HTTP 入口，用于 SSH 到任意业务机器后通过 `curl` 执行脚本或触发补丁控制。这个
入口默认绑定本机地址，适合作为没有 GM 节点时的轻量运维控制面。

```kotlin
nodeLocalOpsHttp {
  host = "127.0.0.1"
  port = 17321
  tokenFile = Path.of("/var/lib/asteria/ops-token")
}
```

如果不使用 starter，也可以直接安装 `NodeLocalOpsHttpModule`。模块启动时会读取 bearer token；`requireToken` 默认为 `true`，
未配置 `token` 或 `tokenFile` 时会拒绝启动。`requireOperator` 默认要求 `X-Asteria-Operator`，`requireReasonForMutations`
默认要求变更类请求提供 `X-Asteria-Reason`。生产环境建议 token 文件只允许运维用户或服务账号读取，并优先绑定 loopback
或受控管理网。

请求链路是：Ktor 路由先校验 bearer token、operator 和 reason，再把 header 转成 `NodeLocalOpsPrincipal`；JSON body 或
multipart form
被转换成 `ScriptExecutionCommand`；同步入口调用 `ScriptRuntime.executeAll`，异步入口调用 `ScriptJobService.submit`
；成功或失败都会写入
`NodeLocalOpsAuditSink`。本地 HTTP 审计只证明本入口收到了请求，脚本级允许/拒绝和执行审计仍由 `ScriptPolicy`、
`ScriptAuditSink` 和
`ScriptJobAuditSink` 决定。

上机器后可以先查看接口自描述：

```bash
curl http://127.0.0.1:17321/ops
```

```bash
curl -X POST http://127.0.0.1:17321/ops/scripts/execute \
  -H "Authorization: Bearer $(cat /var/lib/asteria/ops-token)" \
  -H "X-Asteria-Operator: mikai" \
  -H "X-Asteria-Reason: repair-player" \
  -F 'target={"type":"entity","kind":"player","ids":["1001"]}' \
  -F 'artifact=@./fix-player.groovy'
```

multipart 上传中，`artifact=@./fix-player.groovy` 会从 `.groovy` 推断 engine 为 `groovy`，`artifact=@./repair.jar` 会推断为
`jar`；
其他文件名必须显式提供 `engine` 字段。JSON 请求可用 `bodyText` 或 `bodyBase64` 传脚本内容。入口只检查最大字节数和基本字段形状，是否允许
groovy/jar、是否要求签名/审批/权限仍由业务安装的 `ScriptPolicy` 决定。

当前接口包括：

- `GET /ops`：返回认证头、限制、接口列表和请求示例。
- `GET /ops/health`：本地入口健康检查。
- `POST /ops/scripts/execute`：同步执行脚本并返回批量结果。
- `POST /ops/scripts/jobs`：提交异步脚本任务。
- `GET /ops/scripts/jobs`、`GET /ops/scripts/jobs/{jobId}`、`GET /ops/scripts/jobs/{jobId}/summary`、
  `GET /ops/scripts/jobs/{jobId}/items`、`GET /ops/scripts/jobs/{jobId}/items/{itemId}`：查询任务、汇总和 item。
- `POST /ops/scripts/jobs/{jobId}/cancel`、`POST /ops/scripts/jobs/{jobId}/items/{itemId}/cancel`、
  `POST /ops/scripts/jobs/{jobId}/items/{itemId}/retry`、`POST /ops/scripts/jobs/{jobId}/failed-items/retry`：取消或重试任务。
- `GET /ops/patches`、`GET /ops/patches/{patchId}`、`GET /ops/patches/node-results`：查询补丁 descriptor 和节点结果。
- `POST /ops/patches/{patchId}/apply`、`POST /ops/patches/{patchId}/disable`、`POST /ops/patches/reconcile`：触发补丁应用、
  停用或本节点 desired-state reconcile。

这个入口只负责本机 HTTP 鉴权和请求转换。脚本是否允许执行仍然由业务安装的 `ScriptPolicy` 决定；高风险环境应要求
`X-Asteria-Reason`、工单号、审批号，并通过 `NodeLocalOpsAuditSink` 写入独立审计。
