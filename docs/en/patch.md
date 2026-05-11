# Runtime Patches

Patch modules wrap hot-replaceable logic as persisted `RuntimePatchDescriptor` metadata that can be applied, disabled,
and audited on one node or across a cluster.

## Core Objects

- `RuntimePatchDescriptor`: GM/repository patch metadata, including artifact, compatible versions, target nodes, and
  lifecycle status.
- `RuntimePatch`: node-local execution identity, containing only patch id and repository-assigned revision.
- `PatchRuntime`: current node patch runtime. It only executes already-selected `RuntimePatch` values.
- `RuntimePatchRepository`: patch metadata repository. It stores descriptors and assigns increasing revisions.
- `RuntimePatchPluginResolver`: resolves a patch artifact into an installable `RuntimePatchPlugin`.
- `RuntimePatchPlugin`: the patch lifecycle entry point that declares install and uninstall logic.
- `RuntimePatchInstallContext`: the context used by plugins to declare replacements that the runtime can commit and
  roll back. It also exposes the current node `NodeRuntime`, so business registries can be read from
  `context.runtime.services`.
- `PatchableRegistry` / `PatchableServiceRegistry`: patchable handler slots or service slots.
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
    reconcileInterval = 1.minutes
})
```

`patch-jar` provides a jar artifact resolver, `patch-mongodb` provides Mongo repositories and GridFS artifact storage,
`patch-pekko` provides Pekko cluster-control integration, and `patch-zookeeper` provides ZooKeeper repositories,
artifact storage, and node-result storage.

With `starter-game-server-pekko`, business code usually wires durable repositories and artifact storage directly:

```kotlin
runtimePatches(
    version = BuildInfo.version,
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

The `version` overload derives `appName`, node address, and roles from the running Pekko node. Use the explicit
`PatchEnvironment` overload only when the host application owns those values itself.

## Patch Lifecycle

Patch descriptors declare compatible applications, versions, and targets. A node can expire incompatible descriptors
during startup, then run a desired-state reconciliation: descriptors that are `Enabled` in the repository and match the
current `PatchEnvironment` are selected, converted into `RuntimePatch(id, revision)`, and handed to the local
`PatchRuntime`. `PatchRuntime` only handles patch executions that have already been selected for this node. After a node
restart, GM does not
need to push patches again; if the repository and artifact store are durable, the node reloads enabled metadata and
restores patch layers from the stored jars.
`PatchModule` also runs node-local desired-state reconciliation periodically according to `reconcileInterval`; the
default is one minute, and `null` disables it. Periodic reconciliation compensates for nodes that were absent from the
active member view during apply, transient network failures, and nodes that recover after the initial apply attempt.
When the runtime provides `ClusterViewService`, Pekko patch control uses that view as the target node source. Configured
nodes that are currently not reachable are recorded as `Unreachable` instead of being silently skipped.
`PekkoPatchControlModule` can also accept an explicit `PatchNodeProvider`; otherwise it uses `ClusterViewService`,
`ClusterTopologyProvider`, and finally active Pekko members in that order.

Patch descriptors may carry `PatchRequirements` for required roles, modules, and capabilities. GM creation also reads
the jar manifest attributes `Asteria-Patch-Roles`, `Asteria-Patch-Modules`, and `Asteria-Patch-Capabilities` when the
request does not provide requirements. If a node provider is available, GM validates that the selected target nodes
match those requirements before saving the descriptor.

Patch precedence is defined by the repository-assigned `revision`. Business code does not need to provide an ordering
field. `RuntimePatchRepository.save` assigns a revision for new descriptors and assigns a new revision again when an
existing descriptor is replaced. Multiple patches that replace the same handler or service are replayed by revision, so
the newer patch wins. Disabling the newer patch falls back to the previous remaining layer. Lifecycle state changes
should use `updateStatus`, which preserves the descriptor revision.

Jar resolution first checks the manifest `Patch-Class`, then falls back to `ServiceLoader<RuntimePatchPlugin>`. Artifact
checksums use the `sha256:<hex>` format, and artifact stores must verify bytes before returning them.

## Activation Flow

GM or the publishing flow writes the patch jar to artifact storage, then writes the `RuntimePatchDescriptor` to the
repository. For single-node application, `PatchApplicationService` loads the descriptor from the repository, checks
status,
compatible versions, and target nodes, then asks `RuntimePatchPluginResolver` to load a `RuntimePatchPlugin`.
`PatchRuntime` receives only `RuntimePatch(id, revision)` and the plugin.

Inside `RuntimePatchPlugin.install`, the plugin declares replacement relationships. Registry-layer mutations require a
`PatchRegistryMutationScope`, which is created by `PatchRuntime` and passed only while committing or rolling back a
recorded operation. Business patch code declares replacements through the install context instead of writing registry
layers directly.

Business code usually exposes patchable targets through a binding object and registers it in the node
`ServiceRegistry` during startup:

```kotlin
class GamePatchBindings(
    val playerServices: PatchableServiceRegistry,
    val playerMessageRegistry: PatchableMessageHandlerRegistry<PlayerContext, PlayerMessage>,
    val playerEventRegistry: PatchableEventHandleRegistry<PlayerContext>,
)

context.services.register(GamePatchBindings::class, GamePatchBindings(...))
```

Patch plugin examples:

```kotlin
class PlayerServicePatch : RuntimePatchPlugin {
    override suspend fun install(context: RuntimePatchInstallContext) {
        val bindings = context.runtime.services.get<GamePatchBindings>()
        context.services.replace(
            bindings.playerServices,
            PlayerActivityService::class,
            PatchedPlayerActivityService(),
        )
    }
}

class LoginPatch : RuntimePatchPlugin {
    override suspend fun install(context: RuntimePatchInstallContext) {
        val bindings = context.runtime.services.get<GamePatchBindings>()
        context.messageHandlers.replace(
            bindings.playerMessageRegistry,
            LoginReq::class,
            PatchedLoginHandler(),
        )
    }
}

class LevelPatch : RuntimePatchPlugin {
    override suspend fun install(context: RuntimePatchInstallContext) {
        val bindings = context.runtime.services.get<GamePatchBindings>()
        context.eventHandlers.replaceEventType(
            bindings.playerEventRegistry,
            PlayerLevelChanged::class,
            key = eventHandleKey(PlayerLevelChangedHandler::class),
        ) { handlerContext, event, publisher ->
            handlerContext.player.quest.onLevelChanged(event.newLevel)
        }
    }
}
```

The runtime runs patch installation to record an install plan, validates that each target key currently exists, and
then commits the operations. If installation, validation, or commit fails, already committed operations are rolled back
in reverse order and the apply call returns `PatchApplyResult.Failed`. If the patch is already applied in the same
runtime, repeated apply is ignored.

`PatchableRegistry` keeps original base entries and replacement layers ordered by `revision/id`. Business reads through
`get`, `require`, or `snapshot` see the current view after base entries and all active layers are merged. `replace`
writes only the current patch layer and never mutates the base entry; `remove(id)` removes only that patch layer and
rebuilds the active view from the base and remaining layers. The fallback order is therefore: the newer revision patch,
an older still-enabled patch, and finally the original base entry. `PatchableServiceRegistry` is the same mechanism for
type-keyed services.

When disabling a patch, `PatchApplicationService.disable` first updates repository status to `Disabled`, then calls the
local `PatchRuntime.remove`. Remove calls the plugin's `uninstall` for side effects the framework cannot track, then
removes replacement layers declared through `RuntimePatchInstallContext` and lets registries fall back automatically.
The
resolver may evict jar/classloader cache after disable.

Cluster application is coordinated by `PatchClusterApplicationService`. It gets nodes from `PatchNodeProvider`, selects
targets by descriptor compatibility and target rules, invokes apply or disable on each node through `PatchNodeClient`,
and stores every attempt in the node-result repository. Cluster patching should not assume that every node succeeds at
the same time; GM or operations workflows should use each node's `Applied`, `Removed`, `Ignored`, or `Failed` result to
decide whether to retry, roll back, or escalate.

`patch-pekko` ships a default Pekko serializer binding for patch-control messages, node status responses, and apply
results through `reference.conf`. Applications should not need to bind these messages to Java serialization manually.

## ZooKeeper Storage

`patch-zookeeper` groups data by app/version so operations can inspect one running version with zkCli:

```text
{root}/apps/{appName}/versions/{appVersion}/patches/{patchId}/metadata
{root}/apps/{appName}/versions/{appVersion}/artifacts/{artifactKey}/metadata
{root}/apps/{appName}/versions/{appVersion}/artifacts/{artifactKey}/content
{root}/apps/{appName}/versions/{appVersion}/node-results/{patchId}/{nodeKey}/{attempt}
{root}/index/patches/{patchId}
{root}/counters/patch-revision
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

Only replacements made through `RuntimePatchInstallContext` entry points such as `services`, `messageHandlers`, and
`eventHandlers` are tracked and rolled back automatically. Threads, external hooks, or global state changed by a plugin
must be cleaned up in `uninstall`.

Use a recording context when an operator or validation step needs to inspect the slots a patch would touch before
committing it:

```kotlin
val plan = plugin.recordInstallPlan(RuntimePatch(PatchId("fix-login"), revision), runtime)
plan.validate()
println(plan.replacements)
```

`recordInstallPlan` runs `install` and records replacement declarations without committing replacement layers.

Batch apply is not a transaction. If one patch fails, earlier successfully applied patches remain active. When disabling
a patch, repository state may already be updated; a runtime remove result of `false` only means the current runtime did
not have that patch or had already removed it.
