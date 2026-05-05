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
        application = "game",
        version = "1.2.3",
        nodeId = "world-1",
    )

    repository(MongoRuntimePatchRepository(database))
    resolver(JarRuntimePatchPluginResolver(classLoader = javaClass.classLoader))
    applyOnStart = true
    expireIncompatibleOnStart = true
})
```

`patch-jar` 提供 jar artifact resolver，`patch-mongodb` 提供 Mongo repository 和 GridFS artifact store，`patch-pekko` 提供
Pekko 集群控制适配。

## 补丁生命周期

补丁应该声明自己兼容的应用、版本和能力。节点启动时可以自动过期不兼容补丁，再应用仍然启用的补丁。

集群补丁不应该假设所有节点同时成功。`PatchClusterApplicationService` 会记录每个节点结果；GM 或运维流程应该基于结果决定重试、回滚或人工介入。

补丁应用顺序由 `PatchOrder` 决定：更高 `priority` 更先应用，同 priority 下更小 `sequence` 更先应用。`sequence` 应由
`RuntimePatchRepository.nextSequence()` 原子生成，保证补丁创建顺序稳定。

Jar resolver 会优先读取 manifest 中的 `Patch-Class`，失败时再尝试 `ServiceLoader<RuntimePatchPlugin>`。artifact checksum
使用 `sha256:<hex>` 格式，artifact store 在返回 bytes 前必须校验 checksum。

## 使用边界

运行时补丁适合小范围逻辑修复和临时运维能力，不适合替代正常版本发布。补丁代码必须可重复应用或能在 repository
状态中识别已应用版本，避免节点重启后重复注册同一个 hook。

`RuntimePatchPlugin.install` 里只有通过 `PatchInstallContext.replace` / `replaceService`
这类框架入口做的替换会被自动追踪并回滚。插件自己启动的线程、注册的外部 hook 或修改的全局状态，必须在 `uninstall` 中清理。

批量 apply 不是整体事务。某个 patch 失败时，之前已经成功应用的 patch 会保持生效。禁用补丁时，repository 状态可能已经更新，而本节点
runtime remove 返回 false 只表示当前 runtime 没有该 patch 或已经移除。
