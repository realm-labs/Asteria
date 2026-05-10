# 贡献点聚合

`foundation-contribution` 和 `foundation-contribution-ksp` 提供通用的“实现类清单生成”能力。它适合 activity service、任务 handler、GM command、配置 validator 这类业务扩展点：框架负责在编译期收集实现类，业务侧决定怎么实例化、建索引、注册 service 或接入热补丁。

它不是 DI 容器，也不会在运行期扫描 classpath。生成物是一份静态 Kotlin 清单。

## 接入

```kotlin
dependencies {
    implementation("io.github.realm-labs.asteria:foundation-contribution:<version>")
    ksp("io.github.realm-labs.asteria:foundation-contribution-ksp:<version>")
}
```

如果业务模块还没有启用 KSP，需要同时应用 `com.google.devtools.ksp` 插件。

## 声明贡献

```kotlin
interface ActivityService {
    val key: ActivityKey
}

@AsteriaContribution(contract = ActivityService::class, order = 100)
object SevenDayActivityService : ActivityService {
    override val key = ActivityKey("seven_day")
}
```

`contract` 是这个贡献要进入的列表类型。KSP 会校验被标记的类或 object 必须实现这个 contract。

`order` 只影响生成清单顺序。排序规则是先按 `order`，再按实现类全限定名，保证不同机器和不同编译顺序下生成结果稳定。

贡献声明必须满足：

- public
- 非 abstract
- 不能声明类型参数
- `object`，或者有零参构造函数的 class
- 实现 `contract`

## Catalog

`@AsteriaContributionCatalog` 不是必须的。没有 catalog 时，KSP 会按 contract 生成默认对象：

```kotlin
// contract: com.example.ActivityService
object GeneratedActivityServiceContributions
```

如果需要固定生成包名、对象名或 chunk 大小，可以声明 catalog：

```kotlin
@AsteriaContributionCatalog(
    contract = ActivityService::class,
    packageName = "com.example.generated",
    className = "GeneratedActivityServices",
    chunkSize = 200,
)
object ActivityServiceCatalog
```

同一个 contract 只能有一个 catalog。catalog 只配置生成物，不代表一个运行时 service。

## 生成物

对上面的 `ActivityService`，KSP 会生成：

```kotlin
object GeneratedActivityServices {
    val CONTRIBUTIONS: List<AsteriaContributionDescriptor<ActivityService>>

    fun createAll(): List<ActivityService>

    val ALL: List<ActivityService>
}
```

`CONTRIBUTIONS` 是最底层的描述列表。每个元素包含实现类型、order 和一个零参 `create` 工厂：

```kotlin
data class AsteriaContributionDescriptor<T : Any>(
    val implementationType: KClass<out T>,
    val order: Int = 0,
    val create: () -> T,
)
```

`createAll()` 每次调用都会通过 descriptor 重新创建一份列表。对于 object 来说仍然是同一个 object；对于 class 来说会重新调用构造函数。

`ALL` 是 `val ALL = createAll()`，在生成对象初始化时创建一次，后续复用这份列表。需要每次安装模块都拿新实例时，用 `createAll()`；需要自己控制索引或热补丁时，用 `CONTRIBUTIONS`。

当 contribution 数量超过 `chunkSize` 时，生成器会拆出内部 chunk：

```kotlin
internal object GeneratedActivityServicesChunk0 {
    val CONTRIBUTIONS: List<AsteriaContributionDescriptor<ActivityService>>
}
```

主对象只负责把所有 chunk 的 `CONTRIBUTIONS` 聚合回来，避免单个 Kotlin 文件过大。

## 业务侧建索引

贡献聚合只生成 list，不规定 key 类型。业务可以把同一批贡献建成任意结构：

```kotlin
val services = GeneratedActivityServices.createAll()

val byKey = services.associateBy { it.key }
val byType = services.groupBy { it.key.type }
val ordered = services.sortedBy { it.key.openDay }
```

key 可以是字符串、enum、`KClass`、data class、组合 key 或业务自己的矩阵坐标。不要把这些索引规则塞进通用注解里。

## 与热补丁配合

需要热更时，不要把单个实现直接注册成固定 service。先用 generated list 构建业务 registry，再把 registry 注册到 `ServiceRegistry`：

```kotlin
val registry = PatchableRegistry(
    GeneratedActivityServices.createAll().associateBy { it.key }
)

context.services.register(ActivityServiceRegistry::class, ActivityServiceRegistry(registry))
```

补丁替换的是 registry slot：

```kotlin
context.replace(
    registry,
    ActivityKey("seven_day"),
    PatchedSevenDayActivityService,
)
```

这样基础清单来自编译期生成，运行期读的是 `PatchableRegistry` 的当前视图。是否允许新增 key、是否只允许替换已有 key、是否需要多索引同步，都由业务 registry 定义。

## 常见误用

- 不要把 contribution 当成运行期插件发现机制。它只收集当前编译单元里 KSP 能看到的源码符号。
- 不要在注解里表达复杂 key。复杂 key 应该是 contract 的属性或业务 factory 的产物。
- 不要让贡献 class 依赖构造参数。需要运行时依赖时，定义 factory contract，让 factory 从 `ModuleContext.services` 创建真正的业务对象。
- 不要直接把 `ALL` 当成全局可变状态。需要独立生命周期或测试隔离时，用 `createAll()`。
