# Persistence

Persistence modules are designed for actor-local in-memory state. An actor owns its `DataManager`; loading, caching,
flushing, and unloading happen inside the actor's serialized boundary.

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

Actors usually call `loadEager()` during startup, use `getOrLoad<T>()` or `use<T> { ... }` during message handling, and
call `tick()` or `flush()` from timers.

## Load Policies

- `Eager`: loaded when the actor starts; use for core data.
- `Lazy`: loaded on first access and kept until actor stop.
- `UnloadableLazy`: loaded on scoped access, then eligible for flush and idle unload; must be accessed through `use`.

References from `UnloadableLazy` data must not escape the `use` block. The framework uses `DataLease` to prevent
business code from continuing to use stale objects after unload.

## Mongo Tracked Wrappers

`persistence-mongodb-annotations` and `persistence-mongodb-ksp` generate actor-local tracked wrappers. The annotated DTO
remains the Mongo driver serialization shape; the generated wrapper turns business mutations into Mongo `$set` /
`$unset` patches.

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

Generated code usually contains a tracked wrapper and helper. Business code mutates the wrapper, and the Mongo runtime
collects dirty paths for batched writes. Helpers may also include scan plans and constructors for tracked or scanned
tables. Scan mode is useful when comparing existing DTOs; tracked-wrapper mode is useful when actor code mutates state
directly.

## Annotation Rules

- `@AsteriaMongoId`: every Mongo entity must have exactly one id.
- `@AsteriaMongoField`: overrides the Mongo field name.
- `@AsteriaMongoIgnore`: excludes a property from generated tracking and persistence mapping.
- `@AsteriaMongoScanIgnore`: excludes a persisted property from scan-based dirty tracking; use when another write path
  owns it.
- `@AsteriaMongoScanWholeField`: maps are scanned by key by default; mark a map when whole-field writes are required.
- `@AsteriaMongoValue`: the project guarantees that the type is safe to persist through the Mongo driver.

## Boundaries

`DataManager` assumes calls come from the owning actor or an equivalent single-threaded boundary. Do not expose one
`DataManager` to concurrent threads.

Mongo KSP rejects unstable mutable object graphs. Prefer immutable data classes for custom types. Use
`@AsteriaMongoValue` only when the project already provides a codec or explicitly guarantees serialization safety.

Raw `querySnapshots()` results from Mongo tables are detached snapshots; mutating them is not tracked. To mutate data,
query keys and re-enter tracked `use`, or use the helper-provided mapper entry point.

Nullable collection properties are not a good fit for generated tracked wrappers. Use empty collections, nullable
wrapper objects, or explicit `@AsteriaMongoValue` types to model missing state.
