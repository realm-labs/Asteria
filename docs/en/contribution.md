# Contribution Aggregation

`foundation-contribution` and `foundation-contribution-ksp` provide generic implementation-list generation. They are
useful for business extension points such as activity services, task handlers, GM commands, and config validators:
Asteria collects implementations at compile time, while business code decides how to instantiate them, build indexes,
register services, or connect them to runtime patches.

This is not a DI container and it does not scan the classpath at runtime. The output is a static Kotlin catalog.

## Setup

```kotlin
dependencies {
    implementation("io.github.realm-labs.asteria:foundation-contribution:<version>")
    ksp("io.github.realm-labs.asteria:foundation-contribution-ksp:<version>")
}
```

If the business module does not already use KSP, also apply the `com.google.devtools.ksp` plugin.

## Declaring Contributions

```kotlin
interface ActivityService {
    val key: ActivityKey
}

@AsteriaContribution(contract = ActivityService::class, order = 100)
object SevenDayActivityService : ActivityService {
    override val key = ActivityKey("seven_day")
}
```

`contract` is the list type this contribution belongs to. KSP verifies that the annotated class or object implements
that contract.

`order` affects only generated-list order. Contributions are sorted by `order`, then by implementation qualified name,
so generated output is stable across machines and compiler symbol order.

Contribution declarations must be:

- public
- concrete
- non-generic
- an `object`, or a class with a zero-argument constructor
- assignable to `contract`

## Catalog

`@AsteriaContributionCatalog` is optional. Without it, KSP derives the generated object from the contract:

```kotlin
// contract: com.example.ActivityService
object GeneratedActivityServiceContributions
```

Use a catalog when the generated package, object name, or chunk size must be fixed:

```kotlin
@AsteriaContributionCatalog(
    contract = ActivityService::class,
    packageName = "com.example.generated",
    className = "GeneratedActivityServices",
    chunkSize = 200,
)
object ActivityServiceCatalog
```

Only one catalog is allowed per contract. A catalog configures generated code; it is not a runtime service.

## Generated API

For `ActivityService`, KSP generates:

```kotlin
object GeneratedActivityServices {
    val CONTRIBUTIONS: List<AsteriaContributionDescriptor<ActivityService>>

    fun createAll(): List<ActivityService>

    val ALL: List<ActivityService>
}
```

`CONTRIBUTIONS` is the low-level descriptor list. Each descriptor contains the implementation type, order, and a
zero-argument factory:

```kotlin
data class AsteriaContributionDescriptor<T : Any>(
    val implementationType: KClass<out T>,
    val order: Int = 0,
    val create: () -> T,
)
```

`createAll()` creates a new list every time it is called. Objects still return the same object instance; classes call
their constructor again.

`ALL` is `val ALL = createAll()`: it is initialized once with the generated object and then reused. Use `createAll()`
when each module install needs fresh instances. Use `CONTRIBUTIONS` when business code wants custom indexing or patch
integration.

When the contribution count exceeds `chunkSize`, the generator emits internal chunks:

```kotlin
internal object GeneratedActivityServicesChunk0 {
    val CONTRIBUTIONS: List<AsteriaContributionDescriptor<ActivityService>>
}
```

The main object aggregates chunk `CONTRIBUTIONS`, keeping individual Kotlin files from growing too large.

## Business Indexes

Contribution aggregation generates a list only. Business code decides the key and index shape:

```kotlin
val services = GeneratedActivityServices.createAll()

val byKey = services.associateBy { it.key }
val byType = services.groupBy { it.key.type }
val ordered = services.sortedBy { it.key.openDay }
```

Keys can be strings, enums, `KClass`, data classes, composite keys, or business matrix coordinates. Keep those indexing
rules in the business registry instead of the generic annotation.

## Runtime Patches

For hot patching, avoid registering one implementation as a fixed service. Build a business registry from the generated
base list and register that registry as the stable service:

```kotlin
val registry = PatchableRegistry(
    GeneratedActivityServices.createAll().associateBy { it.key }
)

context.services.register(ActivityServiceRegistry::class, ActivityServiceRegistry(registry))
```

Patch code reads the business registry from the current node `ServiceRegistry`, then replaces registry slots:

```kotlin
fun RuntimePatchInstallContext.replaceActivityService(
    registry: PatchableRegistry<ActivityKey, ActivityService>,
    key: ActivityKey,
    service: ActivityService,
) {
    replaceSlot(registry, key, service)
}

val activities = context.runtime.services.get<ActivityServiceRegistry>()
context.replaceActivityService(activities.registry, ActivityKey("seven_day"), PatchedSevenDayActivityService)
```

The base list comes from compile-time generation, while runtime reads go through the `PatchableRegistry` active view.
Whether a patch can add new keys, replace only existing keys, or update multiple indexes is a business-registry
decision.

## Common Mistakes

- Do not treat contributions as runtime plugin discovery. The processor sees source symbols in the current KSP build.
- Do not encode complex keys in the annotation. Complex keys belong to contract properties or factory output.
- Do not give contribution classes constructor dependencies. If runtime dependencies are needed, define a factory
  contract and create the real business object from `ModuleContext.services`.
- Do not treat `ALL` as global mutable state. Use `createAll()` for isolated lifecycles or tests.
