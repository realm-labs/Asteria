package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.RoleKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PatchClusterApplicationServiceTest {
    @Test
    fun clusterApplicationSelectsTargetNodesAndPersistsResults() = runBlocking {
        val patch = patch(
            id = "player-fix",
            target = PatchTarget.Roles(setOf(RoleKey("player"))),
        )
        val repository = InMemoryRuntimePatchRepository(listOf(patch))
        val results = InMemoryRuntimePatchNodeResultRepository()
        val service = PatchClusterApplicationService(
            repository = repository,
            nodes = PatchNodeProvider {
                listOf(
                    node("player-1", "pekko://game@127.0.0.1:2551", "player"),
                    node("world-1", "pekko://game@127.0.0.1:2552", "world"),
                )
            },
            client = nodeClient { _, patchId -> PatchApplyResult.Applied(patchId, operationCount = 1) },
            results = results,
        )

        val applied = service.apply(patch.id)

        assertEquals(true, applied.succeeded)
        assertEquals(listOf("pekko://game@127.0.0.1:2551"), applied.results.map { it.address })
        assertEquals(RuntimePatchNodeStatus.Applied, applied.results.single().status)
        assertEquals(1, results.list(RuntimePatchNodeResultQuery(patchId = patch.id)).size)
    }

    @Test
    fun clusterApplicationRecordsNodeFailures() = runBlocking {
        val patch = patch("broken")
        val service = PatchClusterApplicationService(
            repository = InMemoryRuntimePatchRepository(listOf(patch)),
            nodes = PatchNodeProvider { listOf(node("player-1", "pekko://game@127.0.0.1:2551", "player")) },
            client = nodeClient { _, _ -> error("boom") },
        )

        val applied = service.apply(patch.id)

        assertEquals(false, applied.succeeded)
        assertEquals(RuntimePatchNodeStatus.Failed, applied.results.single().status)
        assertEquals("boom", applied.results.single().message)
    }

    @Test
    fun clusterDisableRecordsRemovedNodes() = runBlocking {
        val patch = patch("disable")
        val service = PatchClusterApplicationService(
            repository = InMemoryRuntimePatchRepository(listOf(patch)),
            nodes = PatchNodeProvider { listOf(node("player-1", "pekko://game@127.0.0.1:2551", "player")) },
            client = object : PatchNodeClient {
                override suspend fun apply(
                    node: PatchNode,
                    patchId: PatchId,
                ): PatchApplyResult {
                    return PatchApplyResult.Applied(patchId, 1)
                }

                override suspend fun disable(
                    node: PatchNode,
                    patchId: PatchId,
                ): Boolean {
                    return true
                }
            },
        )

        val disabled = service.disable(patch.id)

        assertEquals(true, disabled.succeeded)
        assertEquals(RuntimePatchNodeStatus.Removed, disabled.results.single().status)
    }

    private fun node(
        nodeId: String,
        address: String,
        role: String,
    ): PatchNode {
        return PatchNode(
            nodeId = nodeId,
            address = address,
            appName = "game",
            version = "1.0.0",
            roles = setOf(RoleKey(role)),
        )
    }

    private fun patch(
        id: String,
        target: PatchTarget = PatchTarget.AllNodes,
    ): RuntimePatchDescriptor {
        return RuntimePatchDescriptor(
            id = PatchId(id),
            artifact = PatchArtifact("$id.jar", "sha256:$id"),
            compatibility = PatchCompatibility("game", setOf("1.0.0")),
            name = id,
            target = target,
            revision = 1,
        )
    }

    private fun nodeClient(
        apply: suspend (PatchNode, PatchId) -> PatchApplyResult,
    ): PatchNodeClient {
        return object : PatchNodeClient {
            override suspend fun apply(
                node: PatchNode,
                patchId: PatchId,
            ): PatchApplyResult {
                return apply(node, patchId)
            }

            override suspend fun disable(
                node: PatchNode,
                patchId: PatchId,
            ): Boolean {
                return false
            }
        }
    }
}
