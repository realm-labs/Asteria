# 持久化

持久化模块面向 actor-local 内存态：actor 拥有自己的 `DataManager`，数据加载、缓存、flush 和卸载都发生在 actor 的串行边界内。

## DataManager

```kotlin
class PlayerProfileData(
    private val repository: PlayerProfileRepository,
    private val playerId: Long,
) : MemData, AutoFlushMemData {
    lateinit var profile: PlayerProfile

    override suspend fun load() {
        profile = repository.load(playerId)
    }

    override suspend fun flush(): Boolean {
        repository.save(profile)
        return true
    }
}

val profileModule = dataModule<Long, PlayerProfileData>(
    bucket = DataBucket("profile", DataLoadPolicy.Eager),
) { scope ->
    PlayerProfileData(repository, scope.entityId)
}
```

actor 启动时通常调用 `loadEager()`；消息处理时用 `getOrLoad<T>()` 或 `use<T> { ... }`；timer 中周期性调用 `tick()` 或
`flush()`。

## 加载策略

- `Eager`：actor 启动时加载，适合核心数据。
- `Lazy`：第一次访问时加载，加载后常驻，适合不一定使用的数据。
- `UnloadableLazy`：第一次访问时加载，空闲后可 flush 并卸载，必须通过 `use` 访问。

`UnloadableLazy` 的返回引用不能逃逸出 `use` 块。框架用 `DataLease` 防止业务在卸载后继续使用过期对象。

## Mongo 跟踪 wrapper

`persistence-mongodb-annotations` 和 `persistence-mongodb-ksp` 用注解生成可跟踪的 actor-local wrapper。DTO 仍然是 Mongo
driver 的序列化形状，wrapper 负责把业务修改转成 `$set` / `$unset`。

```kotlin
@AsteriaMongoEntity(collection = "players")
data class PlayerDocument(
    @AsteriaMongoId
    val playerId: Long,
    val name: String,
    val bag: Map<Int, ItemStack> = emptyMap(),
)

data class ItemStack(
    val itemId: Int,
    val count: Int,
)
```

生成代码通常包含 tracked wrapper 和 helper。业务逻辑修改 wrapper，Mongo runtime 收集 dirty path 并批量写入。生成 helper
通常还包含 scan plan、tracked table 构造和 scanned table 构造。scan 模式适合从现有 DTO 对比 dirty 字段；tracked wrapper
模式适合业务在 actor 内直接修改并实时记录 dirty path。

## 注解约定

- `@AsteriaMongoId`：每个 Mongo entity 必须有且只有一个 id。
- `@AsteriaMongoField`：覆盖 Mongo 字段名。
- `@AsteriaMongoIgnore`：字段完全不参与生成和持久化映射。
- `@AsteriaMongoScanIgnore`：扫描式 dirty tracking 忽略该字段，适合由其他写路径维护的数据。
- `@AsteriaMongoScanWholeField`：map 默认可按 key 扫描；需要整字段写入时标记。
- `@AsteriaMongoValue`：项目确认可由 Mongo driver 直接处理的值类型。

## 使用边界

`DataManager` 假定调用来自 owning actor 或等价的单线程边界。不要把同一个 `DataManager` 暴露给多个线程并发调用。

Mongo KSP 校验会拒绝不稳定的可变对象图。遇到自定义类型时，优先把它建模成不可变 data class；只有项目已经提供 codec
或确认可安全序列化时才使用 `@AsteriaMongoValue`。

Mongo table 的 raw `querySnapshots()` 返回 detached snapshot，修改它不会被追踪。需要修改数据时，用 key 查询后重新进入
tracked `use`，或者使用 helper 提供的 mapper 入口。

nullable collection 属性不适合作为生成 tracked wrapper 的字段。用空集合、nullable wrapper object，或明确的
`@AsteriaMongoValue` 类型表达缺省状态。
