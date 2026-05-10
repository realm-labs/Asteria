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

Generated code usually contains:

- `Tracked<Entity>`: the mutable actor-local wrapper. Property setters, nested objects, and map/list/set facades enqueue
  dirty operations.
- `<Entity>Mongo` helper: collection metadata, wrapping functions, tracked document data/table constructors, and scan
  plans when scan mode is available.
- Nested tracked value wrappers: generated when data classes or collection elements need field-level tracking. Deep
  mutable objects that cannot produce stable field-level updates fall back to whole-field writes at the nearest stable
  boundary.

Tracked-wrapper mode is useful when actor code mutates state directly and wants dirty paths recorded as mutations
happen. Scan mode is useful when comparing existing DTOs, usually for migrations or reuse of an existing object model.

## Dirty Flush Mechanism

Each loaded document or row owns a `MongoTrackedDocumentRuntime`. Wrapper setters enqueue `Set`, `Unset`, or `Delete`
operations into a `MongoPendingWriteQueue`; the queue merges repeated operations per document and removes redundant
hierarchical paths, for example an update to `profile` makes an older `profile.name` update unnecessary.

`flush()` drains current pending writes and turns them into Mongo bulk writes. On success, covered journal sequences are
acknowledged; on failure, unflushed writes are requeued for the next flush. `MongoKeyedDocumentTable` also keeps a dirty
row queue, and `flushSome(budget)` flushes part of it by row-count and duration budget so actor timers can spread write
load over time.

Creating a new document enqueues `$set` operations for every field except `_id`. Deleting a document uses a
whole-document
delete and suppresses unflushed field updates for that document. These queues are actor-local; they are not cross-thread
write queues.

## Annotation Rules

- `@AsteriaMongoEntity`: marks a Mongo DTO, declares the collection, and can override generated wrapper/helper names.
- `@AsteriaMongoId`: every Mongo entity must have exactly one id.
- `@AsteriaMongoField`: overrides the Mongo field name.
- `@AsteriaMongoIgnore`: excludes a property from generated tracking and persistence mapping.
- `@AsteriaMongoScanIgnore`: excludes a persisted property from scan-based dirty tracking; use when another write path
  owns it.
- `@AsteriaMongoScanWholeField`: maps are scanned by key by default; mark a map when whole-field writes are required.
- `@AsteriaMongoValue`: the project guarantees that the type is safe to persist through the Mongo driver.

KSP validates non-blank collections, unique ids, and field types that can be tracked or persisted. It rejects recursive
data classes, unstable mutable object graphs, nullable collections, and other models that would produce ambiguous dirty
results.

## Boundaries

`DataManager` assumes calls come from the owning actor or an equivalent single-threaded boundary. Do not expose one
`DataManager` to concurrent threads.

Prefer immutable data classes for custom types. Use `@AsteriaMongoValue` only when the project already provides a codec
or explicitly guarantees serialization safety.

Raw `querySnapshots()` results from Mongo tables are detached snapshots; mutating them is not tracked. To mutate data,
query keys and re-enter tracked `use`, or use the helper-provided mapper entry point.

Nullable collection properties are not a good fit for generated tracked wrappers. Use empty collections, nullable
wrapper objects, or explicit `@AsteriaMongoValue` types to model missing state.
