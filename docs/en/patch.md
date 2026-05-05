# Runtime Patches

Patch modules wrap hot-replaceable logic as `RuntimePatch` objects that can be applied, disabled, and audited on one
node or across a cluster.

## Core Objects

- `PatchRuntime`: current node patch runtime.
- `RuntimePatchRepository`: patch metadata repository.
- `RuntimePatchPluginResolver`: resolves a patch artifact into an installable plugin.
- `PatchApplicationService`: applies, disables, and checks compatibility on one node.
- `PatchClusterApplicationService`: selects target nodes and records per-node results.

## Module Setup

```kotlin
install(PatchModule {
    environment = PatchEnvironment(
        appName = "game",
        version = "1.2.3",
        nodeAddress = "world-1",
    )

    repository(MongoRuntimePatchRepository(database))
    resolver(JarRuntimePatchPluginResolver(classLoader = javaClass.classLoader))
    applyOnStart = true
    expireIncompatibleOnStart = true
})
```

`patch-jar` provides a jar artifact resolver, `patch-mongodb` provides Mongo repositories and GridFS artifact storage,
and `patch-pekko` provides Pekko cluster-control integration.

## Patch Lifecycle

Patches should declare compatible applications, versions, and targets. A node can expire incompatible patches during
startup, then apply patches that are still enabled.

Cluster patching should not assume that every node succeeds at the same time. `PatchClusterApplicationService` records
each node result; GM or operations workflows decide whether to retry, roll back, or escalate.

Patch order is defined by `PatchOrder`: higher `priority` is applied first, and lower `sequence` is applied first within
the same priority. `sequence` should be generated atomically by `RuntimePatchRepository.nextSequence()`.

Jar resolution first checks the manifest `Patch-Class`, then falls back to `ServiceLoader<RuntimePatchPlugin>`. Artifact
checksums use the `sha256:<hex>` format, and artifact stores must verify bytes before returning them.

## Boundaries

Runtime patches are suitable for small logic repairs and temporary operational capabilities. They should not replace
normal releases. Patch code must be repeatable or able to detect already-applied state so node restarts do not register
duplicate hooks.

Only replacements made through `PatchInstallContext.replace` / `replaceService` are tracked and rolled back
automatically. Threads, external hooks, or global state changed by a plugin must be cleaned up in `uninstall`.

Batch apply is not a transaction. If one patch fails, earlier successfully applied patches remain active. When disabling
a patch, repository state may already be updated; a runtime remove result of `false` only means the current runtime did
not have that patch or had already removed it.
