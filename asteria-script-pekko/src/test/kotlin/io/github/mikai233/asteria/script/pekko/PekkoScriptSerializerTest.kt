package io.github.mikai233.asteria.script.pekko

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptTarget
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializationExtension
import scala.jdk.javaapi.FutureConverters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PekkoScriptSerializerTest {
    @Test
    fun serializerRoundTripsScriptMessages() = runBlocking {
        val system = ActorSystem.create("script-serializer-direct-${System.nanoTime()}")
        val serializer = PekkoScriptSerializer(system as ExtendedActorSystem)

        try {
            val command = ScriptExecutionCommand(
                executionId = "script-1",
                target = ScriptTarget.Entity(EntityKind("player"), "10001"),
                artifact = artifact(),
            )
            val result = ScriptExecutionResult(
                executionId = "script-1",
                success = false,
                target = "10001",
                error = "denied",
            )
            val nodeCommand = ExecuteNodeScript(command, originNodeAddress = "pekko://game@127.0.0.1:25520")
            val actorCommand = ExecuteActorScript("script-2", artifact(), ScriptTarget.ActorPath("/user/player"))
            val entityCommand = ExecuteEntityActorScript("10001", "script-3", artifact(), command.target)

            assertRoundTrip(serializer, command)
            assertRoundTrip(serializer, result)
            assertRoundTrip(serializer, nodeCommand)
            assertRoundTrip(serializer, actorCommand)
            assertRoundTrip(serializer, entityCommand)
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }

    @Test
    fun referenceConfigBindsScriptMessagesToSerializer() = runBlocking {
        val system = ActorSystem.create("script-serializer-${System.nanoTime()}")
        try {
            val serializer = SerializationExtension.get(system).findSerializerFor(
                ScriptExecutionCommand("script-1", ScriptTarget.AllNodes, artifact()),
            )

            assertIs<PekkoScriptSerializer>(serializer)
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }

    private fun assertRoundTrip(serializer: PekkoScriptSerializer, message: Any) {
        val bytes = serializer.toBinary(message)
        val decoded = serializer.fromBinary(bytes, serializer.manifest(message))
        assertEquals(message, decoded)
    }

    private fun artifact(): ScriptArtifact {
        return ScriptArtifact(
            name = "fix-state",
            engine = "jar",
            body = byteArrayOf(1, 2, 3),
            extra = byteArrayOf(4, 5),
            checksum = "sha256:test",
        )
    }
}
