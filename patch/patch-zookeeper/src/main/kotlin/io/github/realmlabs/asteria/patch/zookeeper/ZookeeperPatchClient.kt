package io.github.realmlabs.asteria.patch.zookeeper

import kotlinx.coroutines.future.await
import org.apache.curator.x.async.AsyncCuratorFramework
import org.apache.curator.x.async.api.CreateOption
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.data.Stat
import java.nio.charset.StandardCharsets.UTF_8

internal class ZookeeperPatchClient(
    private val client: AsyncCuratorFramework,
) {
    suspend fun incrementCounter(path: String): Long {
        while (true) {
            val current = readWithStat(path) ?: try {
                upsert(path, "1".toByteArray(UTF_8), createOnly = true)
                return 1
            } catch (_: KeeperException.NodeExistsException) {
                continue
            }
            val next = current.bytes.toString(UTF_8).toLong() + 1
            try {
                client.setData()
                    .withVersion(current.version)
                    .forPath(path, next.toString().toByteArray(UTF_8))
                    .await()
                return next
            } catch (_: KeeperException.BadVersionException) {
                continue
            } catch (_: KeeperException.NoNodeException) {
                continue
            }
        }
    }

    suspend fun upsert(
        path: String,
        bytes: ByteArray,
        createOnly: Boolean = false,
    ) {
        try {
            client.create()
                .withOptions(setOf(CreateOption.createParentsIfNeeded))
                .forPath(path, bytes)
                .await()
        } catch (error: KeeperException.NodeExistsException) {
            if (createOnly) throw error
            client.setData()
                .forPath(path, bytes)
                .await()
        }
    }

    suspend fun read(path: String): ByteArray? {
        return try {
            client.data.forPath(path).await()
        } catch (_: KeeperException.NoNodeException) {
            null
        }
    }

    suspend fun children(path: String): List<String> {
        return try {
            client.children.forPath(path).await().sorted()
        } catch (_: KeeperException.NoNodeException) {
            emptyList()
        }
    }

    suspend fun deleteIfExists(path: String) {
        try {
            client.delete().forPath(path).await()
        } catch (_: KeeperException.NoNodeException) {
        }
    }

    private suspend fun readWithStat(path: String): ZnodeBytes? {
        return try {
            val stat = Stat()
            ZnodeBytes(client.data.storingStatIn(stat).forPath(path).await(), stat.version)
        } catch (_: KeeperException.NoNodeException) {
            null
        }
    }

    private class ZnodeBytes(
        val bytes: ByteArray,
        val version: Int,
    )
}
