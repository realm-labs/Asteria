package io.github.realmlabs.asteria.cluster.pekko

import io.github.realmlabs.asteria.cluster.config.ClusterConfigNodeReloadResult
import io.github.realmlabs.asteria.cluster.config.ClusterConfigNodeReloadStatus
import io.github.realmlabs.asteria.cluster.config.ClusterConfigNodeStatus
import io.github.realmlabs.asteria.config.ConfigRevision
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializationExtension
import scala.jdk.javaapi.FutureConverters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PekkoClusterConfigControlSerializerTest {
    @Test
    fun serializerRoundTripsControlMessagesAndReplies(): Unit = runBlocking {
        val system = ActorSystem.create("cluster-config-control-serializer-direct-${System.nanoTime()}")
        val serializer = PekkoClusterConfigControlSerializer(system as ExtendedActorSystem)

        try {
            assertRoundTrip(serializer, PekkoClusterConfigControlMessage.GetStatus)
            assertRoundTrip(serializer, PekkoClusterConfigControlMessage.Reload)
            assertRoundTrip(serializer, status())
            assertRoundTrip(serializer, reloadResult())
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }

    @Test
    fun referenceConfigBindsConfigControlMessagesToSerializer(): Unit = runBlocking {
        val system = ActorSystem.create("cluster-config-control-serializer-${System.nanoTime()}")
        try {
            val serialization = SerializationExtension.get(system)

            assertIs<PekkoClusterConfigControlSerializer>(
                serialization.findSerializerFor(PekkoClusterConfigControlMessage.GetStatus),
            )
            assertIs<PekkoClusterConfigControlSerializer>(serialization.findSerializerFor(status()))
            assertIs<PekkoClusterConfigControlSerializer>(serialization.findSerializerFor(reloadResult()))
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }

    private fun assertRoundTrip(
        serializer: PekkoClusterConfigControlSerializer,
        message: Any,
    ) {
        val bytes = serializer.toBinary(message)
        val decoded = serializer.fromBinary(bytes, serializer.manifest(message))
        assertEquals(message, decoded)
    }

    private fun status(): ClusterConfigNodeStatus {
        return ClusterConfigNodeStatus(
            nodeId = "player-1",
            address = "pekko://game@127.0.0.1:25520",
            roles = setOf("player"),
            revision = ConfigRevision("2026.05.12", "checksum-1"),
            reachable = true,
            message = "last reload failed",
        )
    }

    private fun reloadResult(): ClusterConfigNodeReloadResult {
        return ClusterConfigNodeReloadResult(
            nodeId = "player-1",
            address = "pekko://game@127.0.0.1:25520",
            roles = setOf("player"),
            previousRevision = ConfigRevision("2026.05.11", "checksum-0"),
            currentRevision = ConfigRevision("2026.05.12", "checksum-1"),
            status = ClusterConfigNodeReloadStatus.Succeeded,
        )
    }
}
