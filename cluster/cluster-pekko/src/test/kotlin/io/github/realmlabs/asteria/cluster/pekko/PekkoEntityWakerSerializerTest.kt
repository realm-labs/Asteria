package io.github.realmlabs.asteria.cluster.pekko

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializationExtension
import scala.jdk.javaapi.FutureConverters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PekkoEntityWakerSerializerTest {
    @Test
    fun serializerRoundTripsControlAndStatusMessages(): Unit = runBlocking {
        val system = ActorSystem.create("entity-waker-serializer-direct-${System.nanoTime()}")
        val serializer = PekkoEntityWakerSerializer(system as ExtendedActorSystem)

        try {
            assertRoundTrip(serializer, PekkoEntityWakerCommand.Reconcile)
            assertRoundTrip(serializer, PekkoEntityWakerCommand.WakeTargets("world", listOf("10001", 10002L, 10003)))
            assertRoundTrip(serializer, PekkoEntityWakerCommand.CancelTargets("world", listOf("10004", 10005L)))
            assertRoundTrip(serializer, PekkoEntityWakerCommand.GetStatus("world", targetLimit = 12))
            assertRoundTrip(serializer, status())
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }

    @Test
    fun referenceConfigBindsWakerMessagesToSerializer(): Unit = runBlocking {
        val system = ActorSystem.create("entity-waker-serializer-${System.nanoTime()}")
        try {
            val commandSerializer = SerializationExtension.get(system).findSerializerFor(
                PekkoEntityWakerCommand.GetStatus(),
            )
            val statusSerializer = SerializationExtension.get(system).findSerializerFor(status())

            assertIs<PekkoEntityWakerSerializer>(commandSerializer)
            assertIs<PekkoEntityWakerSerializer>(statusSerializer)
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }

    private fun assertRoundTrip(
        serializer: PekkoEntityWakerSerializer,
        message: Any,
    ) {
        val bytes = serializer.toBinary(message)
        val decoded = serializer.fromBinary(bytes, serializer.manifest(message))
        assertEquals(message, decoded)
    }

    private fun status(): PekkoEntityWakerStatus {
        return PekkoEntityWakerStatus(
            tasks = listOf(
                PekkoEntityWakeTaskStatus(
                    name = "world",
                    entityKind = "World",
                    desired = 100,
                    pending = 12,
                    inFlight = 4,
                    retrying = 2,
                    completed = 80,
                    cancelled = 1,
                    failed = 1,
                    exhausted = 1,
                    currentConcurrency = 16,
                    targets = PekkoEntityWakeTargetStatusSamples(
                        pending = listOf("10001"),
                        inFlight = listOf("10002"),
                        retrying = listOf("10003"),
                        completed = listOf("10004"),
                        cancelled = listOf("10005"),
                        failed = listOf(PekkoEntityWakeFailureStatus("10006", attempts = 3, message = "timeout")),
                        exhausted = listOf(PekkoEntityWakeFailureStatus("10007", attempts = 10, message = null)),
                    ),
                ),
            ),
        )
    }
}
