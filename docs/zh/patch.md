# 运行时补丁

补丁模块用于把可热替换的逻辑包装成 `RuntimePatch`，在单节点或集群范围应用、停用和审计。

## 核心对象

- `PatchRuntime`：当前节点已安装的补丁运行态。
- `RuntimePatchRepository`：补丁元数据仓库。
- `RuntimePatchPluginResolver`：把补丁 artifact 解析成可安装 plugin。
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
})
```

`patch-jar` 提供 jar artifact resolver，`patch-mongodb` 提供 Mongo repository 和 GridFS artifact store，`patch-pekko` 提供
Pekko 集群控制适配，`patch-zookeeper` 提供 ZooKeeper repository、artifact store 和节点结果仓库。

如果使用 `starter-game-server-pekko`，业务通常直接接入持久化 repository 和 artifact store：

```kotlin
runtimePatches(
    environment = PatchEnvironment(
        appName = "game",
        version = BuildInfo.version,
        nodeAddress = pekkoAddress,
        roles = setOf(RoleKey("player")),
    ),
    repository = ZookeeperRuntimePatchRepository(asyncZk, "/asteria/prod/runtime-patches"),
    artifactStore = ZookeeperPatchArtifactStore(
        client = asyncZk,
        appName = "game",
        appVersion = BuildInfo.version,
        rootPath = "/asteria/prod/runtime-patches",
    ),
    cacheDirectory = Paths.get("data/patch-cache"),
)
```

## 补丁生命周期

补丁应该声明自己兼容的应用、版本和目标节点。节点启动时可以自动过期不兼容补丁，再对本节点执行一次 desired-state
reconcile：repository 中 `Enabled` 且匹配当前 `PatchEnvironment` 的补丁会被应用；本地 runtime 中已经存在但 repository
里不再匹配的补丁会被移除。节点重启后不依赖 GM 推送，只要 repository 和 artifact store 是持久化的，就能从 enabled
metadata 重新加载 jar 并恢复 patch layer。

集群补丁不应该假设所有节点同时成功。`PatchClusterApplicationService` 会记录每个节点结果；GM 或运维流程应该基于结果决定重试、回滚或人工介入。

补丁应用顺序由 `PatchOrder` 决定：更高 `priority` 更先应用，同 priority 下更小 `sequence` 更先应用。`sequence` 应由
`RuntimePatchRepository.nextSequence()` 原子生成，保证补丁创建顺序稳定。

Jar resolver 会优先读取 manifest 中的 `Patch-Class`，失败时再尝试 `ServiceLoader<RuntimePatchPlugin>`。artifact checksum
使用 `sha256:<hex>` 格式，artifact store 在返回 bytes 前必须校验 checksum。

## ZooKeeper 存储

`patch-zookeeper` 按 app/version 聚合数据，便于运维用 zkCli 查看当前版本的补丁：

```text
{root}/apps/{appName}/versions/{appVersion}/patches/{patchId}/metadata
{root}/apps/{appName}/versions/{appVersion}/artifacts/{artifactKey}/metadata
{root}/apps/{appName}/versions/{appVersion}/artifacts/{artifactKey}/content
{root}/apps/{appName}/versions/{appVersion}/node-results/{patchId}/{nodeKey}/{attempt}
{root}/index/patches/{patchId}
{root}/counters/patch-sequence
{root}/counters/node-results/{patchId}/{address}
```

路径 segment 使用 percent encoding，所以普通名称保持可读；只有 `/`、`:`、`@` 这类不适合放进 znode 名的字符会被编码。
znode data 使用 `ZookeeperPatchCodec`，默认实现是 `JacksonZookeeperPatchCodec`，metadata 是稳定 DTO JSON，而不是直接
Jackson polymorphic domain object。这样 `PatchTarget` 会写成明确的 `type/roles/addresses` 结构，便于排查，也方便后续
schema 演进。

`ZookeeperPatchArtifactStore` 是按单个 app/version 实例化的。GM 上传某个版本的 jar 时，用对应版本的 store；节点启动时也用
自己当前版本的 store 读取 jar。默认大小限制是 768 KiB，适合小补丁 jar。较大的 jar 建议只把 metadata/desired state 放在
ZooKeeper，artifact bytes 放 GridFS、对象存储或 HTTP store。

如果一个 patch 兼容多个版本，`ZookeeperRuntimePatchRepository.save` 会在每个 version 路径下写一份 metadata，并用
`index/patches/{patchId}` 支持 `find(id)`。业务节点启动时只扫描自己的 `appName/version` 路径，不需要全局扫描。

## 使用边界

运行时补丁适合小范围逻辑修复和临时运维能力，不适合替代正常版本发布。补丁代码必须可重复应用或能在 repository
状态中识别已应用版本，避免节点重启后重复注册同一个 hook。

`RuntimePatchPlugin.install` 里只有通过 `PatchInstallContext.replace` / `replaceService`
这类框架入口做的替换会被自动追踪并回滚。插件自己启动的线程、注册的外部 hook 或修改的全局状态，必须在 `uninstall` 中清理。

批量 apply 不是整体事务。某个 patch 失败时，之前已经成功应用的 patch 会保持生效。禁用补丁时，repository 状态可能已经更新，而本节点
runtime remove 返回 false 只表示当前 runtime 没有该 patch 或已经移除。
