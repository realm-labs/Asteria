# Config

The config stack has four layers: runtime snapshots, config centers, config publication, and code generation. A project
can use only `config-core` for local config loading, or add config centers and hot reload.

## Loading a Snapshot

`ConfigModule` uses a `ConfigLoader` to build a complete snapshot during startup. The snapshot is published through
`ConfigService` only after validation succeeds.

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

`ConfigService.current()` returns the last successfully published snapshot. A failed load never replaces the current
snapshot.

## Table Shapes

KSP generates three typed table references:

```kotlin
@AsteriaConfigTable(name = "items", keyType = Int::class)
data class ItemConfig(val id: Int, val name: String)

@AsteriaConfigTable(name = "rank_rewards", shape = AsteriaConfigTableShape.LIST)
data class RankRewardConfig(val rank: Int, val itemId: Int)

@AsteriaConfigTable(name = "global", shape = AsteriaConfigTableShape.SINGLETON)
data class GlobalConfig(val openServerDay: Int)
```

Generated accessors are used like this:

```kotlin
val item = configService.items[1001]
val rewards = configService.rankRewards.all()
val global = configService.global.get()
```

Use `KEYED` for id lookup, `LIST` for ordered rows or project-defined secondary indexes, and `SINGLETON` for one global
payload.

## Code Generation

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

Kotlin config rows can use `@AsteriaConfigTable` directly. Luban-generated Java entities usually cannot be annotated, so
the recommended bridge is a metadata file emitted by the Luban export step:

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

The Gradle plugin turns this metadata into Kotlin markers, then KSP generates `GameConfigTables` and `ConfigService`
extension properties. Generated files are chunked so large config catalogs do not become one oversized Kotlin file.
`tableType` is optional. Without it, keyed accessors return `KeyedConfigTable<K, R>`; with it, generated accessors return
the requested concrete type, such as `MapConfigTable<K, R>` or `OrderedMapConfigTable<K, R>`, so callers can use the
underlying collection API.

## Hot Reload and Change Dispatch

When hot reload is enabled, `ConfigHotReloadService` listens to a `ConfigReloadTrigger` and reloads a complete snapshot
for each signal:

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

Config-center watches are only signal sources. Business logic should not rely on a specific watch event being delivered;
it should reload or reread full state.

`ConfigService.current()` throws before the first successful `load()`. Reload order is load, build runtime components,
validate, publish, and notify listeners. Listeners run synchronously after publish; listener failures do not roll back
the new snapshot, but they make the reload call fail and enter failure monitoring.

Config validation can be written as `ConfigValidator` implementations and aggregated by KSP with
`@AsteriaConfigValidator`:

```kotlin
@AsteriaConfigValidator
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

`validationParallelism` defaults to `1`, preserving serial validation. Values greater than `1` run validators in
parallel, while errors are still aggregated in `GeneratedConfigValidators.ALL` order for stable diagnostics. Validators
should stay deterministic and side-effect free, and should not depend on shared mutable state.

Config-dependent actor updates can be modeled as change handlers:

```kotlin
@AsteriaConfigChangeHandler
class ActivityConfigChangeHandler : ConfigChangeHandler<PlayerActor> {
    override val watchedTables = configTables(GameConfigTables.Activities)

    override fun handleChange(actor: PlayerActor, event: ConfigChangedEvent) {
        actor.syncActivities(event.current)
    }

    override fun catchUp(actor: PlayerActor, snapshot: ConfigSnapshot) {
        actor.syncActivities(snapshot)
    }
}

val dispatcher = ConfigChangeDispatcher(GeneratedPlayerConfigChangeHandlers.ALL)
dispatcher.dispatchIfNew(actor, event, actor.configRevisionTracker)
dispatcher.catchUpIfNew(actor, configService.current(), actor.configRevisionTracker)
```

The framework provides handler aggregation, dependency matching, and revision de-duplication. Projects still decide how
events reach actors and where each actor stores its handled revision.

## Config Center and Publication

`config-center` provides `ConfigStore`, `RuntimeConfigRepository`, and `ConfigCenterReloadTrigger`. Long-lived
background services should prefer the repository or reload trigger wrappers because they rebuild broken watches and
reread state after outages.

`config-publisher` publishes config artifacts and manifests to a config center. The recommended flow is to publish a
complete version directory, then atomically update the `current` pointer. Consumers watch `current` and then read the
full bundle for that revision.

Publication order is artifacts, manifest, history, and `current` pointer. This is not a global transaction across all
backends, so runtime consumers should watch/read `current` and then load the full bundle.

Backend revision semantics differ: etcd revisions are monotonic, ZooKeeper revisions are znode versions, and Nacos tree
watches are closer to children-change notifications. Do not treat backend revisions as the unified config version; use
the publication manifest version.

## Common Mistakes

- Do not treat cluster host, port, seed, or role topology as normal hot-reload config. A running Pekko actor system does
  not change ports or seeds from a table reload.
- Do not rely only on delta events in config change handlers. Actors that start later or miss a reload should use
  `catchUpIfNew` against the current snapshot.
- Do not force annotations into Luban Java entities. Generate marker metadata instead.
- `ConfigStore.watch` does not include the initial snapshot. Call `get` or `children` first; use watch events as reread
  signals.
- `ConfigPath` must be absolute and must not contain trailing slashes or empty path segments.
