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
        nodeAddress = "pekko://game@10.0.0.12:2551",
    )

    repository(MongoRuntimePatchRepository(database))
    resolver(JarRuntimePatchPluginResolver(artifactStore))
    applyOnStart = true
    expireIncompatibleOnStart = true
})
```

`patch-jar` provides a jar artifact resolver, `patch-mongodb` provides Mongo repositories and GridFS artifact storage,
`patch-pekko` provides Pekko cluster-control integration, and `patch-zookeeper` provides ZooKeeper repositories,
artifact storage, and node-result storage.

With `starter-game-server-pekko`, business code usually wires durable repositories and artifact storage directly:

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

## Patch Lifecycle

Patches should declare compatible applications, versions, and targets. A node can expire incompatible patches during
startup, then run a desired-state reconciliation: patches that are `Enabled` in the repository and match the current
`PatchEnvironment` are applied, while locally applied patches that are no longer desired are removed. After a node
restart, GM does not need to push patches again; if the repository and artifact store are durable, the node reloads
enabled metadata and restores patch layers from the stored jars.

Cluster patching should not assume that every node succeeds at the same time. `PatchClusterApplicationService` records
each node result; GM or operations workflows decide whether to retry, roll back, or escalate.

Patch order is defined by `PatchOrder`: higher `priority` is applied first, and lower `sequence` is applied first within
the same priority. `sequence` should be generated atomically by `RuntimePatchRepository.nextSequence()`.

Jar resolution first checks the manifest `Patch-Class`, then falls back to `ServiceLoader<RuntimePatchPlugin>`. Artifact
checksums use the `sha256:<hex>` format, and artifact stores must verify bytes before returning them.

## ZooKeeper Storage

`patch-zookeeper` groups data by app/version so operations can inspect one running version with zkCli:

```text
{root}/apps/{appName}/versions/{appVersion}/patches/{patchId}/metadata
{root}/apps/{appName}/versions/{appVersion}/artifacts/{artifactKey}/metadata
{root}/apps/{appName}/versions/{appVersion}/artifacts/{artifactKey}/content
{root}/apps/{appName}/versions/{appVersion}/node-results/{patchId}/{nodeKey}/{attempt}
{root}/index/patches/{patchId}
{root}/counters/patch-sequence
{root}/counters/node-results/{patchId}/{address}
```

Path segments use percent encoding, so ordinary names stay readable and only characters such as `/`, `:`, and `@` are
escaped. Znode data goes through `ZookeeperPatchCodec`; the default implementation is `JacksonZookeeperPatchCodec`.
Metadata is stable DTO JSON, not Jackson's polymorphic domain-object format. For example, `PatchTarget` is written as
explicit `type/roles/addresses` fields, which is easier to inspect and safer to evolve.

`ZookeeperPatchArtifactStore` is scoped to one app/version. GM uses the store for the version it is publishing, and a
node uses the store for its current version when loading jars. The default artifact size limit is 768 KiB, which is
appropriate for small patch jars. For larger artifacts, keep metadata and desired state in ZooKeeper and store bytes in
GridFS, object storage, or an HTTP-backed store.

If a patch is compatible with multiple versions, `ZookeeperRuntimePatchRepository.save` writes metadata under every
version path and stores an `index/patches/{patchId}` entry for `find(id)`. A business node scans only its own
`appName/version` path during startup.

## Boundaries

Runtime patches are suitable for small logic repairs and temporary operational capabilities. They should not replace
normal releases. Patch code must be repeatable or able to detect already-applied state so node restarts do not register
duplicate hooks.

Only replacements made through `PatchInstallContext.replace` / `replaceService` are tracked and rolled back
automatically. Threads, external hooks, or global state changed by a plugin must be cleaned up in `uninstall`.

Batch apply is not a transaction. If one patch fails, earlier successfully applied patches remain active. When disabling
a patch, repository state may already be updated; a runtime remove result of `false` only means the current runtime did
not have that patch or had already removed it.
