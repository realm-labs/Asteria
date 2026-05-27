# 运行时补丁

补丁模块用于把可热替换的逻辑包装成持久化的 `RuntimePatchDescriptor`，在单节点或集群范围应用、停用和审计。

## 核心对象

- `RuntimePatchDescriptor`：GM/repository 层的补丁元数据，包含 artifact、兼容版本、目标节点和状态。
- `RuntimePatch`：节点执行层的补丁身份，只包含 patch id 和 repository 分配的 revision。
- `PatchRuntime`：当前节点已安装的补丁运行态，只执行已经筛选过的 `RuntimePatch`。
- `RuntimePatchRepository`：补丁元数据仓库，保存 descriptor 并分配递增 revision。
- `RuntimePatchPluginResolver`：把补丁 artifact 解析成可安装的 `RuntimePatchPlugin`。
- `RuntimePatchPlugin`：补丁生命周期入口，负责声明安装和卸载逻辑。
- `RuntimePatchInstallContext`：补丁声明替换操作的上下文，同时暴露当前节点的 `NodeRuntime`，业务 registry 可以从
  `context.runtime.services` 获取。
- `PatchableRegistry` / `PatchableServiceRegistry`：可被补丁替换的 handler slot 或 service slot。
- `PatchApplicationService`：单节点应用补丁、停用补丁、处理兼容性。
- `PatchClusterApplicationService`：选择目标节点并记录每个节点的应用结果。

## 接入模块

```kotlin
install(PatchModule {
    environment = PatchEnvironment(
        appName = "game",
        version = "1.2.3",
        nodeAddress = "pekko://game@10.0.0.12:2551",
    )

    repository(MongoRuntimePatchRepository(database))
    resolver(JarRuntimePatchPluginResolver(artifactStore))
    applyOnStart = true
    expireIncompatibleOnStart = true
    reconcileInterval = 1.minutes
})
```

`patch-jar` 提供 jar artifact resolver，`patch-mongodb` 提供 Mongo repository 和 GridFS artifact store，`patch-pekko` 提供
Pekko 集群控制适配，`patch-config-center` 基于任意 `config-center` 后端提供 repository、小 artifact store、节点结果仓库和
watch 触发的 reconcile。

如果使用 `starter-game-server-pekko`，业务通常直接接入持久化 repository 和 artifact store：

```kotlin
runtimePatches(
    version = BuildInfo.version,
    repository = ConfigCenterRuntimePatchRepository(configStore, "/asteria/prod/runtime-patches"),
    artifactStore = ConfigCenterPatchArtifactStore(
        store = configStore,
        appName = "game",
        appVersion = BuildInfo.version,
        rootPath = "/asteria/prod/runtime-patches",
    ),
    nodeResults = ConfigCenterRuntimePatchNodeResultRepository(configStore, "/asteria/prod/runtime-patches"),
    reconcileTrigger = ConfigCenterPatchReconcileTrigger(configStore, "/asteria/prod/runtime-patches"),
    cacheDirectory = Paths.get("data/patch-cache"),
)
```

`version` overload 会从当前 Pekko 节点推导 appName、节点地址和角色。只有宿主应用自己管理这些信息时，才需要使用显式的
`PatchEnvironment` overload。

## 补丁生命周期

补丁 descriptor 声明自己兼容的应用、版本和目标节点。节点启动时可以自动过期不兼容 descriptor，再对本节点执行一次
desired-state reconcile：repository 中 `Enabled` 且匹配当前 `PatchEnvironment` 的 descriptor 会先被筛选出来，再转换成只含
`id/revision` 的 `RuntimePatch` 交给节点本地 `PatchRuntime` 执行。`PatchRuntime` 只处理已经筛选过的 patch
execution。节点重启后不依赖
GM 推送，只要 repository 和 artifact store 是持久化的，就能从 enabled metadata 重新加载 jar 并恢复 patch layer。
`PatchModule` 还会按 `reconcileInterval` 周期性执行本节点 desired-state reconcile；默认 1 分钟，设置为 `null`
可以关闭。也可以安装 `PatchReconcileTrigger`，例如 `ConfigCenterPatchReconcileTrigger`，让 config-center 变化立即触发本节点
reconcile。周期 reconcile 仍然用于兜底补偿漏掉的 watch 事件、短暂网络故障和节点恢复后的状态对齐。
如果运行时提供 `ClusterViewService`，Pekko patch 控制会优先用集群视图作为目标节点来源；配置中存在但当前不可达的节点会记录为
`Unreachable`，不会被静默跳过。
`PekkoPatchControlModule` 也可以传入显式 `PatchNodeProvider`；没有显式 provider 时会依次使用 `ClusterViewService`、
`ClusterTopologyProvider` 和当前 Pekko active members。

补丁 descriptor 可以携带 `PatchRequirements`，声明所需 role、module 和 capability。GM 创建补丁时，如果请求没有显式传
requirements，会读取 jar manifest 中的 `Asteria-Patch-Roles`、`Asteria-Patch-Modules` 和
`Asteria-Patch-Capabilities`。如果当前有 node provider，GM 会在保存 descriptor 前校验目标节点是否满足这些 requirements。

补丁覆盖顺序由 repository 分配的 `revision` 决定。业务创建 descriptor 时不需要填写顺序字段；保存新 descriptor 或替换已有
descriptor 时，repository 会分配新的递增 revision。多个 patch 替换同一个 handler 或 service 时，revision 更新的 patch 覆盖旧
patch；禁用新 patch 后会回落到前一个仍然存在的 layer。生命周期状态变更走 `updateStatus`，不会改变 descriptor revision。

Jar resolver 会优先读取 manifest 中的 `Patch-Class`，失败时再尝试 `ServiceLoader<RuntimePatchPlugin>`。artifact checksum
使用 `sha256:<hex>` 格式，artifact store 在返回 bytes 前必须校验 checksum。

## 生效流程

GM 或发布流程先把补丁 jar 写入 artifact store，再把 `RuntimePatchDescriptor` 写入 repository。单节点应用时，
`PatchApplicationService` 从 repository 读取 descriptor，检查状态、兼容版本和目标节点，然后交给
`RuntimePatchPluginResolver` 加载 `RuntimePatchPlugin`。`PatchRuntime` 只接收 `RuntimePatch(id, revision)` 和 plugin。

插件在 `RuntimePatchPlugin.install` 中声明替换关系。底层 `PatchSlotRegistry` 的写操作需要
`PatchRegistryMutationScope`，这个 scope 由 `PatchRuntime` 创建并在提交/回滚时传入；业务 patch 代码通过 install context
声明替换，不直接写 registry layer。

业务侧通常把可替换入口集中到一个 binding 对象里，并在节点启动时注册到 `ServiceRegistry`：

`PatchableMessageHandlerRegistry` 和 `RuntimePatchInstallContext.messageHandlers` 来自 `patch-message`；
`PatchableEventHandleRegistry` 和 `RuntimePatchInstallContext.eventHandlers` 来自 `patch-event`。`patch-core` 只提供
runtime 和通用 patchable slot 机制。
如果 message handler 只需要脱离 patch 生命周期的直接替换，可以使用 `HotswapMessageHandlerRegistry`；它会立即修改当前
registry，校验和回滚由调用方负责。
event handler 的同类直接替换模型由 `patch-event` 中的 `HotswapEventHandleRegistry` 提供。

```kotlin
class GamePatchBindings(
    val playerServices: PatchableServiceRegistry,
    val playerMessageRegistry: PatchableMessageHandlerRegistry<PlayerContext, PlayerMessage>,
    val playerEventRegistry: PatchableEventHandleRegistry<PlayerContext>,
)

context.services.register(GamePatchBindings::class, GamePatchBindings(...))
```

补丁插件示例：

```kotlin
class PlayerServicePatch : RuntimePatchPlugin {
    override suspend fun install(context: RuntimePatchInstallContext) {
        val bindings = context.runtime.services.get<GamePatchBindings>()
        context.services.replace(
            bindings.playerServices,
            PlayerActivityService::class,
            PatchedPlayerActivityService(),
        )
    }
}

class LoginPatch : RuntimePatchPlugin {
    override suspend fun install(context: RuntimePatchInstallContext) {
        val bindings = context.runtime.services.get<GamePatchBindings>()
        context.messageHandlers.replace(bindings.playerMessageRegistry, LoginReq::class, PatchedLoginHandler())
    }
}

class LevelPatch : RuntimePatchPlugin {
    override suspend fun install(context: RuntimePatchInstallContext) {
        val bindings = context.runtime.services.get<GamePatchBindings>()
        context.eventHandlers.replaceEventType(
            bindings.playerEventRegistry,
            PlayerLevelChanged::class,
            key = eventHandleKey(PlayerLevelChangedHandler::class),
        ) { handlerContext, event, publisher ->
            handlerContext.player.quest.onLevelChanged(event.newLevel)
        }
    }
}
```

runtime 会先执行补丁安装并记录 install plan，再统一校验目标 key 当前存在，然后按操作提交；如果安装、校验或提交失败，已经提交的操作会按反序回滚，并返回
`PatchApplyResult.Failed`。补丁已经应用过时，同一个 runtime 会忽略重复 apply。

`PatchableRegistry` 保存原始 base entry 和按 `revision/id` 排序的 replacement layer。业务读取 `get`、`require` 或
`snapshot` 时看到的是 base 加所有有效 layer 之后的当前视图。`replace` 只写入当前 patch 的 layer，不修改 base；`remove(id)`
只删除该 patch 的 layer，并重新从 base 和剩余 layer 计算当前视图。因此回退层级是：revision 较新的补丁、较旧但仍启用的补丁、
最后才是原始 base entry。`PatchableServiceRegistry` 是同一机制的类型键 service 版本。

禁用补丁时，`PatchApplicationService.disable` 先把 repository 状态更新为 `Disabled`，再调用本节点 `PatchRuntime.remove`。
remove 会调用 `uninstall` 清理框架无法追踪的副作用，随后删除通过 `RuntimePatchInstallContext` 声明的替换 layer，并让
registry 自动回退到下一层。resolver 可以在禁用后清理 jar/classloader 缓存。

集群应用通过 `PatchClusterApplicationService` 完成。它从 `PatchNodeProvider` 获取节点，按 descriptor 的兼容性和 target
筛选目标，再通过 `PatchNodeClient` 对每个节点发起 apply 或 disable，并把每次尝试写入 node-result
repository。集群补丁不应该假设所有节点同时成功；
GM 或运维流程应该基于每个节点的 `Applied`、`Removed`、`Ignored`、`Failed` 结果决定重试、回滚或人工介入。

`patch-pekko` 通过 `reference.conf` 提供 patch control 消息、节点状态响应和 apply 结果的默认 Pekko serializer binding。
业务项目不需要手动把这些消息绑定到 Java serializer。

## Config-Center 存储

`patch-config-center` 按 app/version 聚合数据，并且只依赖通用 `ConfigStore` API。同一套实现可以运行在
`config-center-zookeeper`、`config-center-nacos`、`config-center-etcd` 和内存实现之上：

```text
{root}/apps/{appName}/versions/{appVersion}/patches/{patchId}
{root}/apps/{appName}/versions/{appVersion}/artifacts/{artifactKey}/metadata
{root}/apps/{appName}/versions/{appVersion}/artifacts/{artifactKey}/content
{root}/apps/{appName}/versions/{appVersion}/node-results/{patchId}__{nodeKey}__{attempt}
{root}/index/patches/{patchId}
{root}/counters/patch-revision
{root}/counters/node-results/{patchId}/{address}
```

路径 segment 使用 percent encoding，所以普通名称保持可读；只有 `/`、`:`、`@` 这类不适合放进路径 segment 的字符会被编码。
存储值使用 `ConfigCenterPatchCodec`，默认实现是 `JacksonConfigCenterPatchCodec`，metadata 是稳定 DTO JSON，而不是直接
Jackson polymorphic domain object。这样 `PatchTarget` 会写成明确的 `type/roles/addresses` 结构，便于排查，也方便后续
schema 演进。

`ConfigCenterPatchArtifactStore` 是按单个 app/version 实例化的。GM 上传某个版本的 jar 时，用对应版本的 store；节点启动时也用
自己当前版本的 store 读取 jar。默认大小限制是 768 KiB，适合小补丁 jar。较大的 jar 建议只把 metadata/desired state 放在
config-center，artifact bytes 放 GridFS、对象存储或 HTTP store。

如果一个 patch 兼容多个版本，`ConfigCenterRuntimePatchRepository.save` 会在每个 version 路径下写一份 metadata，并用
`index/patches/{patchId}` 支持 `find(id)` 和宽查询。业务节点启动或收到 reconcile trigger 时只对齐自己的 `appName/version`
路径。

## 使用边界

运行时补丁适合小范围逻辑修复和临时运维能力，不适合替代正常版本发布。补丁代码必须可重复应用或能在 repository
状态中识别已应用版本，避免节点重启后重复注册同一个 hook。

只有通过 `RuntimePatchInstallContext` 的 `services`、`messageHandlers`、`eventHandlers`
这类入口做的替换会被自动追踪并回滚。message 和 event 入口只有在对应 adapter 模块进入 classpath 后才可用。补丁自己启动的线程、
注册的外部 hook 或修改的全局状态，必须在 `uninstall` 中清理。

安装前需要检查补丁会触碰哪些 slot 时，可以用 recording context：

```kotlin
val plan = plugin.recordInstallPlan(RuntimePatch(PatchId("fix-login"), revision), runtime)
plan.validate()
println(plan.replacements)
```

`recordInstallPlan` 只执行 install 并记录替换声明，不会提交替换 layer。

批量 apply 不是整体事务。某个 patch 失败时，之前已经成功应用的 patch 会保持生效。禁用补丁时，repository 状态可能已经更新，而本节点
runtime remove 返回 false 只表示当前 runtime 没有该 patch 或已经移除。
