package io.github.realmlabs.asteria.config.center.zookeeper

import io.github.realmlabs.asteria.config.center.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.framework.recipes.cache.CuratorCache
import org.apache.curator.framework.recipes.cache.CuratorCacheListener
import org.apache.curator.x.async.AsyncCuratorFramework
import org.apache.curator.x.async.api.CreateOption
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.data.Stat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ZooKeeper-backed [ConfigStore].
 *
 * Revisions use the node `version` field, so compare-and-set semantics are per-znode updates rather than global
 * ordering. Watches are backed by Curator caches and therefore deliver best-effort change notifications; if a cache
 * stops because of an unrecoverable error, higher-level helpers recreate the watch and resync state from ZooKeeper.
 */
class ZookeeperConfigStore(
    private val client: AsyncCuratorFramework,
) : ConfigStore {
    private val syncClient: CuratorFramework = client.unwrap()

    override suspend fun get(path: ConfigPath): ConfigEntry? {
        return try {
            val stat = Stat()
            val bytes = client.data.storingStatIn(stat).forPath(path.value).await()
            ConfigEntry(path, bytes.copyOf(), stat.toRevision())
        } catch (_: KeeperException.NoNodeException) {
            null
        }
    }

    override suspend fun children(path: ConfigPath): List<ConfigEntry> {
        return try {
            client.children.forPath(path.value)
                .await()
                .sorted()
                .mapNotNull { child -> get(path / child) }
        } catch (_: KeeperException.NoNodeException) {
            emptyList()
        }
    }

    override fun watch(
        path: ConfigPath,
        mode: ConfigWatchMode,
    ): ConfigWatch {
        val closed = AtomicBoolean(false)
        val cacheRef = AtomicReference<CuratorCache?>()
        val events = callbackFlow {
            if (closed.get()) {
                close()
                return@callbackFlow
            }
            val cache = when (mode) {
                ConfigWatchMode.Value -> CuratorCache.builder(syncClient, path.value)
                    .withOptions(CuratorCache.Options.SINGLE_NODE_CACHE)
                    .withExceptionHandler { error -> close(error) }
                    .build()

                ConfigWatchMode.Children,
                ConfigWatchMode.Tree,
                    -> CuratorCache.builder(syncClient, path.value)
                    .withExceptionHandler { error -> close(error) }
                    .build()
            }
            cache.listenable().addListener { type, oldData, data ->
                val event = eventOf(type, oldData, data)
                if (event != null && matches(path, mode, event.path)) {
                    trySend(event)
                }
            }
            cacheRef.set(cache)
            cache.start()
            awaitClose {
                cacheRef.compareAndSet(cache, null)
                cache.close()
            }
        }
        return object : ConfigWatch {
            override val events: Flow<ConfigEvent> = events

            override fun close() {
                closed.set(true)
                cacheRef.getAndSet(null)?.close()
            }
        }
    }

    override suspend fun put(
        path: ConfigPath,
        bytes: ByteArray,
        expectedRevision: ConfigRevision?,
    ): ConfigRevision {
        return if (expectedRevision == null) {
            upsert(path, bytes)
        } else {
            putExpected(path, bytes, expectedRevision)
        }
    }

    override suspend fun upsert(
        path: ConfigPath,
        bytes: ByteArray,
    ): ConfigRevision {
        while (true) {
            try {
                return client.setData()
                    .forPath(path.value, bytes.copyOf())
                    .await()
                    .toRevision()
            } catch (_: KeeperException.NoNodeException) {
                try {
                    client.create()
                        .withOptions(setOf(CreateOption.createParentsIfNeeded))
                        .forPath(path.value, bytes.copyOf())
                        .await()
                    return checkExists(path).toRevision()
                } catch (_: KeeperException.NodeExistsException) {
                    continue
                } catch (_: KeeperException.NoNodeException) {
                    continue
                }
            }
        }
    }

    override suspend fun update(
        path: ConfigPath,
        transform: suspend (current: ConfigEntry?) -> ByteArray?,
    ): ConfigEntry? {
        while (true) {
            val current = get(path)
            val bytes = transform(current?.copy(bytes = current.bytes.copyOf())) ?: return null
            try {
                val revision = if (current == null) {
                    create(path, bytes)
                } else {
                    setData(path, bytes, current.revision.version.toInt())
                }
                return ConfigEntry(path, bytes.copyOf(), revision)
            } catch (_: KeeperException.NodeExistsException) {
                continue
            } catch (_: KeeperException.BadVersionException) {
                continue
            } catch (_: KeeperException.NoNodeException) {
                continue
            }
        }
    }

    override suspend fun delete(
        path: ConfigPath,
        expectedRevision: ConfigRevision?,
    ) {
        try {
            client.delete()
                .withVersion(expectedRevision?.version?.toInt() ?: -1)
                .forPath(path.value)
                .await()
        } catch (_: KeeperException.NoNodeException) {
            if (expectedRevision != null) {
                throw ConfigRevisionMismatchException(path, expectedRevision, null)
            }
        } catch (_: KeeperException.BadVersionException) {
            throw ConfigRevisionMismatchException(path, expectedRevision, statOrNull(path)?.toRevision())
        }
    }

    private suspend fun putExpected(
        path: ConfigPath,
        bytes: ByteArray,
        expectedRevision: ConfigRevision,
    ): ConfigRevision {
        return try {
            setData(path, bytes, expectedRevision.version.toInt())
        } catch (_: KeeperException.NoNodeException) {
            throw ConfigRevisionMismatchException(path, expectedRevision, null)
        } catch (_: KeeperException.BadVersionException) {
            throw ConfigRevisionMismatchException(path, expectedRevision, statOrNull(path)?.toRevision())
        }
    }

    private suspend fun create(
        path: ConfigPath,
        bytes: ByteArray,
    ): ConfigRevision {
        client.create()
            .withOptions(setOf(CreateOption.createParentsIfNeeded))
            .forPath(path.value, bytes.copyOf())
            .await()
        return checkExists(path).toRevision()
    }

    private suspend fun setData(
        path: ConfigPath,
        bytes: ByteArray,
        version: Int,
    ): ConfigRevision {
        return client.setData()
            .withVersion(version)
            .forPath(path.value, bytes.copyOf())
            .await()
            .toRevision()
    }

    private suspend fun checkExists(path: ConfigPath): Stat {
        return client.checkExists().forPath(path.value).await()
    }

    private suspend fun statOrNull(path: ConfigPath): Stat? {
        return try {
            checkExists(path)
        } catch (_: KeeperException.NoNodeException) {
            null
        }
    }

    private fun eventOf(
        type: CuratorCacheListener.Type?,
        oldData: ChildData?,
        data: ChildData?,
    ): ConfigEvent? {
        return when (type) {
            CuratorCacheListener.Type.NODE_CREATED,
            CuratorCacheListener.Type.NODE_CHANGED,
                -> data?.toEntry()?.let { ConfigEvent.Upserted(it.path, it) }

            CuratorCacheListener.Type.NODE_DELETED -> {
                val previous = oldData?.toEntry() ?: data?.toEntry()
                val deletedPath = previous?.path ?: data?.path?.let(::ConfigPath) ?: oldData?.path?.let(::ConfigPath)
                deletedPath?.let { ConfigEvent.Deleted(it, previous) }
            }

            null -> null
        }
    }

    private fun ChildData.toEntry(): ConfigEntry {
        return ConfigEntry(ConfigPath(path), data.copyOf(), stat.toRevision())
    }

    private fun matches(
        watchPath: ConfigPath,
        mode: ConfigWatchMode,
        changedPath: ConfigPath,
    ): Boolean {
        return when (mode) {
            ConfigWatchMode.Value -> changedPath == watchPath
            ConfigWatchMode.Children -> changedPath.isChildOf(watchPath)
            ConfigWatchMode.Tree -> changedPath == watchPath || changedPath.isDescendantOf(watchPath)
        }
    }

    private fun Stat.toRevision(): ConfigRevision {
        return ConfigRevision(
            version = version.toString(),
            metadata = mapOf(
                "czxid" to czxid.toString(),
                "mzxid" to mzxid.toString(),
                "ctime" to ctime.toString(),
                "mtime" to mtime.toString(),
            ),
        )
    }
}
