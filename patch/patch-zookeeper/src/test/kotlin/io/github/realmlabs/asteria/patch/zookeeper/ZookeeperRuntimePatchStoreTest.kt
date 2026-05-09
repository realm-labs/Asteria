package io.github.realmlabs.asteria.patch.zookeeper

import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.runBlocking
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.apache.curator.x.async.AsyncCuratorFramework
import java.time.Instant
import kotlin.test.*

class ZookeeperRuntimePatchStoreTest {
    @Test
    fun `repository stores patch metadata under each compatible app version`() = runBlocking {
        withZookeeper { client ->
            val root = "/asteria/test/runtime-patches"
            val paths = ZookeeperPatchPaths(root)
            val repository = ZookeeperRuntimePatchRepository(AsyncCuratorFramework.wrap(client), root)
            val sequence = repository.nextSequence()
            val patch = runtimePatch(sequence = sequence)

            repository.save(patch)

            assertEquals(patch, repository.find(patch.id))
            assertEquals(listOf(patch), repository.list(RuntimePatchQuery(appName = "game", version = "1.0.0")))
            assertEquals(listOf(patch), repository.list(RuntimePatchQuery(appName = "game", version = "1.0.1")))
            assertNotNull(client.checkExists().forPath(paths.patchMetadataPath("game", "1.0.0", patch.id)))
            assertNotNull(client.checkExists().forPath(paths.patchMetadataPath("game", "1.0.1", patch.id)))

            repository.updateStatus(patch.id, PatchStatus.Disabled)

            assertEquals(PatchStatus.Disabled, repository.find(patch.id)?.status)
            assertEquals(emptyList(), repository.list(RuntimePatchQuery(status = PatchStatus.Enabled)))
        }
    }

    @Test
    fun `repository moves metadata when compatible versions change`() = runBlocking {
        withZookeeper { client ->
            val root = "/asteria/test/runtime-patches"
            val paths = ZookeeperPatchPaths(root)
            val repository = ZookeeperRuntimePatchRepository(AsyncCuratorFramework.wrap(client), root)
            val patch = runtimePatch(sequence = repository.nextSequence())
            repository.save(patch)

            val moved = patch.copy(compatibility = PatchCompatibility("game", setOf("1.0.1")))
            repository.save(moved)

            assertNull(client.checkExists().forPath(paths.patchMetadataPath("game", "1.0.0", patch.id)))
            assertNotNull(client.checkExists().forPath(paths.patchMetadataPath("game", "1.0.1", patch.id)))
            assertEquals(listOf(moved), repository.list(RuntimePatchQuery(appName = "game", version = "1.0.1")))
        }
    }

    @Test
    fun `artifact store keeps jar bytes under configured app version`() = runBlocking {
        withZookeeper { client ->
            val root = "/asteria/test/runtime-patches"
            val paths = ZookeeperPatchPaths(root)
            val store = ZookeeperPatchArtifactStore(
                client = AsyncCuratorFramework.wrap(client),
                rootPath = root,
                appName = "game",
                appVersion = "1.0.0",
            )
            val bytes = "patch-jar".encodeToByteArray()

            val artifact = store.save("fix-player-bag.jar", bytes, version = "2026.05.09.1")

            assertContentEquals(bytes, store.load(artifact))
            assertNotNull(client.checkExists().forPath(paths.artifactMetadataPath("game", "1.0.0", artifact)))
            assertNotNull(client.checkExists().forPath(paths.artifactContentPath("game", "1.0.0", artifact)))
        }
    }

    @Test
    fun `node result repository stores attempts under app version path`() = runBlocking {
        withZookeeper { client ->
            val root = "/asteria/test/runtime-patches"
            val paths = ZookeeperPatchPaths(root)
            val repository = ZookeeperRuntimePatchNodeResultRepository(AsyncCuratorFramework.wrap(client), root)
            val patchId = PatchId("fix-player-bag")
            val address = "pekko://game@127.0.0.1:2551"
            val attempt = repository.nextAttempt(patchId, address)
            val result = RuntimePatchNodeResult(
                patchId = patchId,
                nodeId = "player-1",
                address = address,
                appName = "game",
                version = "1.0.0",
                roles = setOf(RoleKey("player")),
                status = RuntimePatchNodeStatus.Applied,
                attempt = attempt,
                operationCount = 2,
                updatedAt = Instant.parse("2026-05-09T00:00:00Z"),
            )

            repository.save(result)

            assertEquals(listOf(result), repository.list(RuntimePatchNodeResultQuery(patchId = patchId)))
            assertNotNull(client.checkExists().forPath(paths.nodeResultPath("game", "1.0.0", patchId, "player-1", 1)))
            assertEquals(2, repository.nextAttempt(patchId, address))
        }
    }

    private fun runtimePatch(sequence: Long): RuntimePatch {
        return RuntimePatch(
            id = PatchId("fix/player:bag"),
            name = "Fix player bag",
            artifact = PatchArtifact("fix-player-bag.jar", "sha256:${"jar".encodeToByteArray().sha256Hex()}"),
            compatibility = PatchCompatibility("game", setOf("1.0.0", "1.0.1")),
            target = PatchTarget.Roles(setOf(RoleKey("player"))),
            priority = 100,
            sequence = sequence,
        )
    }

    private suspend fun withZookeeper(block: suspend (CuratorFramework) -> Unit) {
        TestingServer().use { server ->
            val client = CuratorFrameworkFactory.newClient(
                server.connectString,
                ExponentialBackoffRetry(100, 3),
            )
            client.start()
            client.use { block(client) }
        }
    }

    private fun ByteArray.sha256Hex(): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
    }
}
