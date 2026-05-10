# 配置系统

配置模块分成四层：运行时快照、配置中心、配置发布、代码生成。业务可以只用 `config-core` 本地加载配置，也可以叠加配置中心和热更。

## 加载快照

`ConfigModule` 在启动时通过 `ConfigLoader` 生成完整快照，校验通过后发布给 `ConfigService`。

```kotlin
install(ConfigModule {
    loader {
        DefaultConfigSnapshot(
            revision = ConfigRevision("local-dev"),
            tables = listOf(
                MapConfigTable(
                    name = ConfigTableName("items"),
                    keyType = Int::class,
                    rowType = ItemConfig::class,
                    rows = rows.associateBy { it.id },
                )
            ),
        )
    }

    validator { snapshot ->
        val items = snapshot.requireTable(GameConfigTables.Items)
        check(items[1001] != null) { "items requires id=1001" }
    }
})
```

`ConfigService.current()` 只返回最后一次成功发布的快照。加载失败不会替换当前快照。

## 配置表形态

KSP 生成三种强类型表引用：

```kotlin
@AsteriaConfigTable(name = "items", keyType = Int::class)
data class ItemConfig(val id: Int, val name: String)

@AsteriaConfigTable(name = "rank_rewards", shape = AsteriaConfigTableShape.LIST)
data class RankRewardConfig(val rank: Int, val itemId: Int)

@AsteriaConfigTable(name = "global", shape = AsteriaConfigTableShape.SINGLETON)
data class GlobalConfig(val openServerDay: Int)
```

生成结果的使用方式：

```kotlin
val item = configService.items[1001]
val rewards = configService.rankRewards.all()
val global = configService.global.get()
```

`KEYED` 适合按 id 查询，`LIST` 适合保留顺序或按业务二次索引，`SINGLETON` 适合全局唯一配置。

## 接入代码生成

```kotlin
plugins {
    id("io.github.realm-labs.asteria.config-codegen")
}

asteriaConfigCodegen {
    packageName.set("com.example.game.config.generated")

    configChange {
        receiverType.set("com.example.game.player.PlayerActor")
        className.set("GeneratedPlayerConfigChangeHandlers")
    }

    luban {
        enabled.set(true)
        metadataFile.set(layout.projectDirectory.file("config/luban-tables.json"))
    }
}
```

普通 Kotlin 配置表可以直接加 `@AsteriaConfigTable`。Luban 生成 Java entity 时不能直接改注解，推荐由 Luban 导出步骤额外生成
metadata：

```json
{
  "tables": [
    {
      "name": "items",
      "shape": "KEYED",
      "keyType": "kotlin.Int",
      "rowType": "cfg.item.ItemConfig",
      "tableType": "io.github.realmlabs.asteria.config.MapConfigTable"
    },
    {
      "name": "global",
      "shape": "SINGLETON",
      "rowType": "cfg.global.GlobalConfig"
    }
  ]
}
```

Gradle plugin 会把 metadata 转成 Kotlin marker，再交给 KSP 生成 `GameConfigTables` 和 `ConfigService` 扩展属性。生成器已经按
chunk 拆分大文件，配置表很多时不会把所有 accessor 放进一个 Kotlin 文件。
`tableType` 是可选字段。不配置时，keyed accessor 返回 `KeyedConfigTable<K, R>`；配置后会返回指定的具体表类型，例如
`MapConfigTable<K, R>` 或 `OrderedMapConfigTable<K, R>`，业务代码就可以直接使用底层集合接口。

### 注解和生成物

`@AsteriaConfigTable` 标记一个配置表 row 类型或 marker 类型。KSP 会读取 `name`、`shape`、`keyType`、`rowType`、
`tableType`、`refName` 和 `propertyName`，生成：

- `GameConfigTables`：每张表的强类型引用，例如 `GameConfigTables.Items`。
- `ConfigSnapshot` 扩展属性：从快照读取指定表。
- `ConfigService` 扩展属性：从当前快照读取指定表。
- `META-INF/asteria/codegen-snapshots/config/config.json`：记录本次扫描到的表和变更 handler 模型。

`@AsteriaConfigChangeHandler` 标记一个 `ConfigChangeHandler<Receiver>` 实现。KSP 会校验 receiver 类型，生成
`GeneratedConfigChangeHandlers.ALL` 这类 handler 清单。`ConfigChangeDispatcher` 运行时根据 handler 的 `watchedTables`
和本次 `changedTables` 做匹配，并用 revision tracker 避免同一个 actor 重复处理同一版本。`handle(receiver, snapshot)`
接收当前完整快照；`dispatch(event)` 只调用命中的 handler，`dispatch(snapshot)` 会调用全部 handler。

配置 validator 使用通用 contribution 机制聚合。需要生成 validator 清单时，使用 [贡献点聚合](contribution.md) 的
`@AsteriaContribution(contract = ConfigValidator::class)`。

## 热更和变更分发

开启热更时，`ConfigHotReloadService` 监听 `ConfigReloadTrigger`，收到信号后重新加载完整快照：

```kotlin
install(ConfigModule {
    loader(lubanLoader)

    hotReload {
        trigger(ConfigCenterReloadTrigger(store, ConfigPath("/game/config/current")))
        debounce = 2.seconds
        retryDelay = 5.seconds
    }
})
```

配置中心 watch 只是触发源。业务逻辑不应该依赖某一次 watch 事件必达，而应该以“重新读取完整状态”为准。

`ConfigService.current()` 在首次 `load()` 成功前会抛错。`reload` 的顺序是 load、构建 runtime
components、validate、publish、通知 listeners。listener 在发布后同步执行；listener 抛错不会回滚新快照，但会让本次 reload
调用失败并进入失败监控。

配置校验可以通过 `ConfigValidator` 编写。需要聚合大量 validator 时，用通用 contribution KSP 生成列表；业务模块需要依赖
`foundation-contribution`，并把 `foundation-contribution-ksp` 加到 `ksp` 配置：

```kotlin
@AsteriaContributionCatalog(
  contract = ConfigValidator::class,
  packageName = "com.example.generated.config",
  className = "GeneratedConfigValidators",
)
object ConfigValidatorCatalog

@AsteriaContribution(contract = ConfigValidator::class)
object ItemConfigValidator : ConfigValidator {
  override suspend fun validate(snapshot: ConfigSnapshot): ConfigValidationResult {
    return configValidator { current ->
      val items = current.requireTable(GameConfigTables.Items)
      items.all().forEach { item ->
        check(item.price >= 0, "price must not be negative", items.name, item.id)
      }
    }.validate(snapshot)
  }
}

install(LubanConfigModule {
  validators(GeneratedConfigValidators.ALL)
  validationParallelism = 8
})
```

`validationParallelism` 默认为 `1`，保持串行校验。设置为大于 `1` 后，多个 validator 会并行执行；框架仍按
生成列表顺序聚合错误，保证诊断输出稳定。validator 应保持确定性、无副作用，不要依赖共享可变状态。

业务 actor 根据配置依赖做增量处理时，用配置变更 handler：

```kotlin
@AsteriaConfigChangeHandler
class ActivityConfigChangeHandler : ConfigChangeHandler<PlayerActor> {
    override val watchedTables = configTables(GameConfigTables.Activities)

  override fun handle(actor: PlayerActor, snapshot: ConfigSnapshot) {
        actor.syncActivities(snapshot)
    }
}

val dispatcher = ConfigChangeDispatcher(GeneratedPlayerConfigChangeHandlers.ALL)
dispatcher.dispatchIfNew(actor, event, actor.configRevisionTracker)
dispatcher.dispatchIfNew(actor, configService.current(), actor.configRevisionTracker)
```

框架只负责 handler 清单、依赖匹配和 revision 去重；事件如何送到 actor、actor 的 revision 存在哪里，由业务 runtime 决定。

## 配置中心和发布

`config-center` 提供 `ConfigStore`、`RuntimeConfigRepository` 和 `ConfigCenterReloadTrigger`。后台常驻服务应优先使用
repository 或 reload trigger，因为它们在 watch 中断后会重建 watch 并重新读取状态。

`config-publisher` 用于把配置 artifact 和 manifest 发布到配置中心。推荐发布完整版本目录，再原子更新 current 指针；消费者看到
current 变化后读取对应版本的完整 artifact。

发布顺序是 artifact、manifest、history、`current` pointer。它不是跨后端全局事务，所以运行时消费者只应该 watch/read `current`
，再根据 revision 读取完整 bundle。

默认路径布局通常是 `/asteria/config/current`、`/asteria/config/revisions/{version}` 和
`/asteria/config/history/{version}`。不同配置中心的 revision 语义不同：etcd revision 单调递增，ZooKeeper revision 对应
znode version，Nacos 的树形 watch 更接近 children 变化通知。业务不要把这些后端 revision 当成统一的配置版本号，配置版本应来自发布
manifest。

## 常见误用

- 不要把集群节点 host、port、seed 这类启动拓扑当成普通热更配置。运行中的 Pekko actor system 不会因为配置表变化自动换端口或
  seed。
- 不要在配置变更 handler 中只依赖“差量事件”。actor 新上线或错过热更事件时，应该用当前快照调用 `dispatchIfNew` 补齐状态。
- 不要对 Luban Java entity 强行做注解侵入。用 metadata 生成 marker 更稳。
- `ConfigStore.watch` 不包含初始快照。watch 之前先 `get` 或 `children`，watch 事件只作为后续重读信号。
- `ConfigPath` 必须是绝对路径，不能有尾斜杠和空路径段。
