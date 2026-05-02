package io.github.mikai233.asteria.rpc.protobuf.generator

import io.github.mikai233.asteria.rpc.RpcProtocolProvider
import io.github.mikai233.asteria.rpc.protobuf.ProtobufRpcProtocolContributor
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ProtobufRpcProtocolGeneratorTest {
    @Test
    fun `generates protobuf rpc contributor from metadata`() {
        val workDir = createTempDirectory("asteria-rpc-protocol-generator")
        val metadata = workDir.resolve("rpc-protocol.json")
        val kotlinOutput = workDir.resolve("kotlin")
        val resourcesOutput = workDir.resolve("resources")
        metadata.writeText(
            """
            {
              "messages": [
                {
                  "id": 9001,
                  "type": "com.example.protocol.PushNotice"
                }
              ],
              "entityIds": [
                {
                  "type": "com.example.protocol.SharedPlayerReq",
                  "property": "playerId"
                }
              ],
              "methods": [
                {
                  "id": 1001,
                  "name": "player.query",
                  "mode": "ASK",
                  "requestType": "com.example.protocol.QueryPlayerReq",
                  "responseId": 1002,
                  "responseType": "com.example.protocol.QueryPlayerResp",
                  "target": { "type": "ENTITY", "name": "player" },
                  "entityIdProperty": "playerId"
                },
                {
                  "id": 2001,
                  "name": "world.reload",
                  "mode": "TELL",
                  "requestType": "com.example.protocol.ReloadWorldReq",
                  "target": { "type": "SINGLETON", "name": "world" }
                },
                {
                  "id": 3001,
                  "name": "mail.deliver",
                  "mode": "TELL",
                  "requestType": "com.example.protocol.DeliverMailReq",
                  "target": { "type": "SERVICE", "role": "mail", "path": "/user/mail" }
                }
              ]
            }
            """.trimIndent(),
        )

        ProtobufRpcProtocolGenerator.generate(
            ProtobufRpcGeneratorConfig(
                metadata = metadata,
                kotlinOutput = kotlinOutput,
                resourcesOutput = resourcesOutput,
                packageName = "com.example.generated",
                className = "GeneratedRpcProtocol",
            ),
        )

        val generatedFile = kotlinOutput.resolve(Path("com/example/generated/GeneratedRpcProtocol.kt"))
        assertTrue(generatedFile.exists())
        val generatedCode = generatedFile.readText()
        assertContains(generatedCode, "class GeneratedRpcProtocol : GeneratedProtobufRpcProtocol()")
        assertContains(generatedCode, "builder.message(id = 9_001")
        assertContains(generatedCode, "messageClass = PushNotice::class")
        assertContains(generatedCode, "builder.entityId(SharedPlayerReq::class.java)")
        assertContains(generatedCode, "message.playerId.toString()")
        assertContains(generatedCode, "builder.call(")
        assertContains(generatedCode, "requestClass = QueryPlayerReq::class")
        assertContains(generatedCode, "responseClass = QueryPlayerResp::class")
        assertContains(generatedCode, "target = RpcTarget.Entity(EntityKind(\"player\"))")
        assertContains(generatedCode, "entityIdResolver = { message -> message.playerId.toString() }")
        assertContains(generatedCode, "builder.tell(")
        assertContains(generatedCode, "target = RpcTarget.Singleton(SingletonName(\"world\"))")
        assertContains(generatedCode, "target = RpcTarget.Service(RoleKey(\"mail\"), \"/user/mail\")")

        val providerFile = resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .resolve(RpcProtocolProvider::class.qualifiedName!!)
        assertTrue(providerFile.exists())
        assertContains(providerFile.readText(), "com.example.generated.GeneratedRpcProtocol")
        val contributorFile = resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .resolve(ProtobufRpcProtocolContributor::class.qualifiedName!!)
        assertTrue(contributorFile.exists())
        assertContains(contributorFile.readText(), "com.example.generated.GeneratedRpcProtocol")
    }
}
