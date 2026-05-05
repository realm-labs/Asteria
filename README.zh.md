# Asteria

Asteria 是一个模块化的 Kotlin 游戏服务器运行时。它提供应用模块、角色、实体、消息分发、Pekko 集群适配、协议注册、网关会话、持久化契约、配置、脚本、GM
工具、运行时补丁和可观测性等框架基础能力。

业务项目仍然拥有自己的拓扑和领域模型。`World`、`Home`、`Player`、`Room`、`Match` 这类概念属于业务选择，不是框架强制模型。

## 文档

项目文档按语言组织：

- [English documentation](docs/en/README.md)
- [中文文档](docs/zh/README.md)

建议从这些文档开始：

- [模块地图](docs/zh/module-map.md)
- [应用生命周期](docs/zh/application-lifecycle.md)
- [配置系统](docs/zh/config.md)
- [Pekko 集群](docs/zh/cluster-pekko.md)
- [消息、协议和网关](docs/zh/messaging-protocol-gateway.md)
- [持久化](docs/zh/persistence.md)
- [脚本和 GM](docs/zh/script-and-gm.md)
- [运行时补丁](docs/zh/patch.md)
- [观测和启动器](docs/zh/observability-and-starter.md)

## 快速开始

只引入当前服务实际需要的模块：

```kotlin
dependencies {
    implementation("io.github.realm-labs.asteria:foundation-core:<version>")

    // 按需增加：
    implementation("io.github.realm-labs.asteria:config-core:<version>")
    implementation("io.github.realm-labs.asteria:cluster-pekko:<version>")
    implementation("io.github.realm-labs.asteria:gateway-netty:<version>")
}
```

创建应用、安装模块并启动：

```kotlin
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.core.gameApplication

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
        role("player")
        install(GameRuntimeModule())
    }

    app.launch()
}
```

## 运行约定

- 节点 host、port、role、seed 等启动拓扑是进程启动输入，不会热更新到已经运行的 Pekko actor system。
- 配置表默认只加载一次。只有业务明确支持热更时才启用 `ConfigModule.hotReload`。热更发布的是完整且校验通过的快照。
- 配置中心 watch 只是通知源。后台常驻服务应优先使用 `RuntimeConfigRepository` 和 `ConfigCenterReloadTrigger`，它们会在
  watch 失败后重建并重新读取状态。
- 依赖租约的服务采用 fail closed。Worker ID 和脚本任务 permit 只会在当前租约仍有效时重试临时后端错误。

## 发布

本地验证：

```bash
./gradlew publishToMavenLocal
```

Maven Central 发布通过手动运行 GitHub Actions 中的 `.github/workflows/release.yml` 完成。该 workflow 会处理版本号、创建
release tag、拒绝重复版本，并以 `io.github.realm-labs.asteria` group 发布构件。

需要的仓库 secrets：

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_IN_MEMORY_KEY`
- `SIGNING_IN_MEMORY_KEY_PASSWORD`
- `RELEASE_GITHUB_TOKEN`

首次发布前，需要在 Central Portal 验证 `io.github.realm-labs` namespace，并分发签名公钥。
