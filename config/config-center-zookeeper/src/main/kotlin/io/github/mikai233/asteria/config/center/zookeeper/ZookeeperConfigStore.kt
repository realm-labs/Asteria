package io.github.mikai233.asteria.config.center.zookeeper

import io.github.mikai233.asteria.config.center.ConfigEntry
import io.github.mikai233.asteria.config.center.ConfigEvent
import io.github.mikai233.asteria.config.center.ConfigPath
import io.github.mikai233.asteria.config.center.ConfigRevision
import io.github.mikai233.asteria.config.center.ConfigRevisionMismatchException
import io.github.mikai233.asteria.config.center.ConfigStore
import io.github.mikai233.asteria.config.center.ConfigWatch
import io.github.mikai233.asteria.config.center.ConfigWatchMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.future.await
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.framework.recipes.cache.CuratorCache
import org.apache.curator.framework.recipes.cache.CuratorCacheListener
import org.apache.curator.x.async.AsyncCuratorFramework
import org.apache.curator.x.async.api.CreateOption
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.data.Stat

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
        val cache = when (mode) {
            ConfigWatchMode.Value -> CuratorCache.build(syncClient, path.value, CuratorCache.Options.SINGLE_NODE_CACHE)
            ConfigWatchMode.Children,
            ConfigWatchMode.Tree,
                -> CuratorCache.build(syncClient, path.value)
        }
        val flow = MutableSharedFlow<ConfigEvent>(extraBufferCapacity = 64)
        cache.listenable().addListener { type, oldData, data ->
            val event = eventOf(type, oldData, data)
            if (event != null && matches(path, mode, event.path)) {
                flow.tryEmit(event)
            }
        }
        cache.start()
        return object : ConfigWatch {
            override val events: Flow<ConfigEvent> = flow

            override fun close() {
                cache.close()
            }
        }
    }

    override suspend fun put(
        path: ConfigPath,
        bytes: ByteArray,
        expectedRevision: ConfigRevision?,
    ): ConfigRevision {
        val current = statOrNull(path)
        if (expectedRevision != null && expectedRevision.version.toInt() != (current?.version ?: -1)) {
            throw ConfigRevisionMismatchException(path, expectedRevision, current?.toRevision())
        }

        val stat = if (current == null) {
            if (expectedRevision != null) {
                throw ConfigRevisionMismatchException(path, expectedRevision, null)
            }
            client.create()
                .withOptions(setOf(CreateOption.createParentsIfNeeded))
                .forPath(path.value, bytes.copyOf())
                .await()
            client.checkExists().forPath(path.value).await()
        } else {
            client.setData()
                .withVersion(expectedRevision?.version?.toInt() ?: -1)
                .forPath(path.value, bytes.copyOf())
                .await()
        }
        return stat.toRevision()
    }

    override suspend fun delete(
        path: ConfigPath,
        expectedRevision: ConfigRevision?,
    ) {
        val current = statOrNull(path)
        if (expectedRevision != null && expectedRevision.version.toInt() != (current?.version ?: -1)) {
            throw ConfigRevisionMismatchException(path, expectedRevision, current?.toRevision())
        }
        if (current != null) {
            client.delete()
                .withVersion(expectedRevision?.version?.toInt() ?: -1)
                .forPath(path.value)
                .await()
        }
    }

    private suspend fun statOrNull(path: ConfigPath): Stat? {
        return try {
            client.checkExists().forPath(path.value).await()
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
