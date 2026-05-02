package io.github.mikai233.asteria.protocol.protobuf.generator

import io.github.mikai233.asteria.protocol.protobuf.ProtobufGatewayProtocolContributor
import io.github.mikai233.asteria.protocol.protobuf.ProtobufGatewayProtocolProvider
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ProtobufGatewayProtocolGeneratorTest {
    @Test
    fun `generates gateway protobuf contributor from metadata`() {
        val workDir = createTempDirectory("asteria-gateway-protocol-generator")
        val metadata = workDir.resolve("gateway-protocol.json")
        val kotlinOutput = workDir.resolve("kotlin")
        val resourcesOutput = workDir.resolve("resources")
        metadata.writeText(
            """
            {
              "messages": [
                {
                  "id": 1001,
                  "type": "com.example.protocol.LoginReq",
                  "direction": "CLIENT",
                  "target": { "type": "ENTITY", "name": "player" },
                  "idProperty": "playerId"
                },
                {
                  "id": 1002,
                  "type": "com.example.protocol.LoginResp",
                  "direction": "SERVER"
                },
                {
                  "id": 1003,
                  "type": "com.example.protocol.Ping",
                  "direction": "BIDIRECTIONAL",
                  "target": { "type": "GATEWAY_LOCAL" }
                }
              ]
            }
            """.trimIndent(),
        )

        ProtobufGatewayProtocolGenerator.generate(
            ProtobufGatewayGeneratorConfig(
                metadata = metadata,
                kotlinOutput = kotlinOutput,
                resourcesOutput = resourcesOutput,
                packageName = "com.example.generated",
                className = "GeneratedGatewayProtocol",
            ),
        )

        val generatedFile = kotlinOutput.resolve(Path("com/example/generated/GeneratedGatewayProtocol.kt"))
        assertTrue(generatedFile.exists())
        val generatedCode = generatedFile.readText()
        assertContains(generatedCode, "class GeneratedGatewayProtocol : GeneratedProtobufGatewayProtocol()")
        assertContains(generatedCode, "builder.clientMessage(")
        assertContains(generatedCode, "messageClass = LoginReq::class")
        assertContains(generatedCode, "parser = LoginReq.parser()")
        assertContains(generatedCode, "target = RouteTarget.Entity(EntityKind(\"player\"))")
        assertContains(generatedCode, "idResolver = { message -> message.playerId }")
        assertContains(generatedCode, "builder.serverMessage(")
        assertContains(generatedCode, "builder.bidirectionalMessage(")
        assertContains(generatedCode, "target = RouteTarget.GatewayLocal")

        val providerFile = resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .resolve(ProtobufGatewayProtocolProvider::class.qualifiedName!!)
        assertTrue(providerFile.exists())
        assertContains(providerFile.readText(), "com.example.generated.GeneratedGatewayProtocol")
        val contributorFile = resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .resolve(ProtobufGatewayProtocolContributor::class.qualifiedName!!)
        assertTrue(contributorFile.exists())
        assertContains(contributorFile.readText(), "com.example.generated.GeneratedGatewayProtocol")
    }
}
