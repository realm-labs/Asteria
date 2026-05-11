package io.github.realmlabs.asteria.patch.configcenter

import io.github.realmlabs.asteria.config.center.InMemoryConfigStore
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class ConfigCenterRuntimePatchStoreTest {
    @Test
    fun `repository stores patch metadata under each compatible app version`(): Unit = runBlocking {
        val store = InMemoryConfigStore()
        val root = "/asteria/test/runtime-patches"
        val paths = ConfigCenterPatchPaths(root)
        val repository = ConfigCenterRuntimePatchRepository(store, root)
        val patch = repository.save(runtimePatch())

        assertEquals(patch, repository.find(patch.id))
        assertEquals(listOf(patch), repository.list(RuntimePatchQuery(appName = "game", version = "1.0.0")))
        assertEquals(listOf(patch), repository.list(RuntimePatchQuery(appName = "game", version = "1.0.1")))
        val metadataPath = paths.patchMetadataPath("game", "1.0.0", patch.id)
        assertNotNull(store.get(metadataPath))
        assertNotNull(store.get(paths.patchMetadataPath("game", "1.0.1", patch.id)))
        assertContains(store.get(metadataPath)!!.bytes.decodeToString(), "\"id\":\"fix/player:bag\"")

        repository.updateStatus(patch.id, PatchStatus.Disabled)

        assertEquals(PatchStatus.Disabled, repository.find(patch.id)?.status)
        assertEquals(emptyList(), repository.list(RuntimePatchQuery(status = PatchStatus.Enabled)))
    }

    @Test
    fun `repository moves metadata when compatible versions change`(): Unit = runBlocking {
        val store = InMemoryConfigStore()
        val root = "/asteria/test/runtime-patches"
        val paths = ConfigCenterPatchPaths(root)
        val repository = ConfigCenterRuntimePatchRepository(store, root)
        val patch = repository.save(runtimePatch())

        val moved = repository.save(patch.copy(compatibility = PatchCompatibility("game", setOf("1.0.1"))))

        assertNull(store.get(paths.patchMetadataPath("game", "1.0.0", patch.id)))
        assertNotNull(store.get(paths.patchMetadataPath("game", "1.0.1", patch.id)))
        assertEquals(listOf(moved), repository.list(RuntimePatchQuery(appName = "game", version = "1.0.1")))
    }

    @Test
    fun `artifact store keeps jar bytes under configured app version`(): Unit = runBlocking {
        val store = InMemoryConfigStore()
        val root = "/asteria/test/runtime-patches"
        val paths = ConfigCenterPatchPaths(root)
        val artifactStore = ConfigCenterPatchArtifactStore(
            store = store,
            rootPath = root,
            appName = "game",
            appVersion = "1.0.0",
        )
        val bytes = "patch-jar".encodeToByteArray()

        val artifact = artifactStore.save("fix-player-bag.jar", bytes, version = "2026.05.09.1")

        assertContentEquals(bytes, artifactStore.load(artifact))
        assertNotNull(store.get(paths.artifactMetadataPath("game", "1.0.0", artifact)))
        assertNotNull(store.get(paths.artifactContentPath("game", "1.0.0", artifact)))
    }

    @Test
    fun `node result repository stores attempts under app version path`(): Unit = runBlocking {
        val store = InMemoryConfigStore()
        val root = "/asteria/test/runtime-patches"
        val paths = ConfigCenterPatchPaths(root)
        val patches = ConfigCenterRuntimePatchRepository(store, root)
        val repository = ConfigCenterRuntimePatchNodeResultRepository(store, root)
        val patch = patches.save(runtimePatch())
        val address = "pekko://game@127.0.0.1:2551"
        val attempt = repository.nextAttempt(patch.id, address)
        val result = RuntimePatchNodeResult(
            patchId = patch.id,
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

        assertEquals(listOf(result), repository.list(RuntimePatchNodeResultQuery(patchId = patch.id)))
        assertNotNull(store.get(paths.nodeResultPath("game", "1.0.0", patch.id, "player-1", 1)))
        assertEquals(2, repository.nextAttempt(patch.id, address))
    }

    @Test
    fun `reconcile trigger emits when patch metadata changes`(): Unit = runBlocking {
        val store = InMemoryConfigStore()
        val root = "/asteria/test/runtime-patches"
        val repository = ConfigCenterRuntimePatchRepository(store, root)
        val trigger = ConfigCenterPatchReconcileTrigger(store, root)
        val environment = PatchEnvironment("game", "1.0.0")

        val signal = async {
            withTimeout(1_000.milliseconds) {
                trigger.signals(environment).drop(1).first()
            }
        }
        delay(50.milliseconds)
        repository.save(runtimePatch())

        signal.await()
    }

    private fun runtimePatch(): RuntimePatchDescriptor {
        return RuntimePatchDescriptor(
            id = PatchId("fix/player:bag"),
            artifact = PatchArtifact("fix-player-bag.jar", "sha256:${"jar".encodeToByteArray().sha256Hex()}"),
            compatibility = PatchCompatibility("game", setOf("1.0.0", "1.0.1")),
            requirements = PatchRequirements(roles = setOf(RoleKey("player"))),
            name = "Fix player bag",
            target = PatchTarget.Roles(setOf(RoleKey("player"))),
        )
    }

    private fun ByteArray.sha256Hex(): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
    }
}
