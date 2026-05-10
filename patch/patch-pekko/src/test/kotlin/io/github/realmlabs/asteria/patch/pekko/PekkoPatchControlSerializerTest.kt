package io.github.realmlabs.asteria.patch.pekko

import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.patch.PatchApplyResult
import io.github.realmlabs.asteria.patch.PatchId
import io.github.realmlabs.asteria.patch.PatchNode
import io.github.realmlabs.asteria.patch.PatchNodeStatus
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializationExtension
import scala.jdk.javaapi.FutureConverters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PekkoPatchControlSerializerTest {
    @Test
    fun referenceConfigBindsPatchControlMessages(): Unit = runBlocking {
        val system = ActorSystem.create("patch-control-serializer-test")
        try {
            val serialization = SerializationExtension.get(system)

            assertIs<PekkoPatchControlSerializer>(
                serialization.findSerializerFor(PekkoPatchControlMessage.Apply(PatchId("fix-player"))),
            )
            assertIs<PekkoPatchControlSerializer>(
                serialization.findSerializerFor(PatchApplyResult.Failed(PatchId("fix-player"), "missing binding")),
            )
            assertIs<PekkoPatchControlSerializer>(
                serialization.findSerializerFor(PekkoPatchDisableResult(removed = false, message = "missing patch")),
            )
            assertIs<PekkoPatchControlSerializer>(
                serialization.findSerializerFor(
                    PatchNode(
                        nodeId = "player-1",
                        address = "pekko://game@127.0.0.1:2551",
                        appName = "game",
                        version = "1.0.0",
                        roles = setOf(RoleKey("player")),
                        modules = setOf("player-module"),
                        capabilities = setOf("player-runtime"),
                        status = PatchNodeStatus.Reachable,
                    ),
                ),
            )
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }

    @Test
    fun serializerRoundTripsApplyResult(): Unit = runBlocking {
        val system = ActorSystem.create("patch-control-roundtrip")
        try {
            val serializer = PekkoPatchControlSerializer(system as ExtendedActorSystem)
            val result = PatchApplyResult.Failed(PatchId("fix-player"), "missing binding")

            val decoded = serializer.fromBinary(serializer.toBinary(result), serializer.manifest(result))

            assertEquals(result, decoded)
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }
}
