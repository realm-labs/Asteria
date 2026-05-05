package io.github.realmlabs.asteria.protocol.protobuf.generator

import com.google.protobuf.DescriptorProtos
import io.github.realmlabs.asteria.protocol.protobuf.ProtobufGatewayProtocolContributor
import io.github.realmlabs.asteria.protocol.protobuf.ProtobufGatewayProtocolProvider
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ProtobufGatewayProtocolGeneratorTest {
    @Test
    fun `generates gateway protobuf contributor from metadata`() {
        val workDir = createTempDirectory("asteria-gateway-protocol-generator")
        val metadata = workDir.resolve("gateway-protocol.json")
        val descriptorSet = workDir.resolve("gateway-protocol.pb")
        val kotlinOutput = workDir.resolve("kotlin")
        val resourcesOutput = workDir.resolve("resources")
        metadata.writeText(
            """
            {
              "messages": [
                {
                  "id": 1001,
                  "type": "com.example.protocol.LoginReq",
                  "direction": "C2S",
                  "name": "login"
                },
                {
                  "id": 1002,
                  "type": "com.example.protocol.LoginResp",
                  "direction": "S2C",
                  "responseTo": "login"
                },
                {
                  "id": 1003,
                  "type": "com.example.protocol.Ping",
                  "direction": "BIDIRECTIONAL",
                  "target": { "type": "GATEWAY_LOCAL" }
                }
              ],
              "routes": [
                {
                  "message": "com.example.protocol.LoginReq",
                  "target": { "type": "ENTITY", "name": "player" },
                  "idProperty": "playerId"
                }
              ]
            }
            """.trimIndent(),
        )
        descriptorSet.writeBytes(testDescriptorSet().toByteArray())

        ProtobufGatewayProtocolGenerator.generate(
            ProtobufGatewayGeneratorConfig(
                metadata = metadata,
                kotlinOutput = kotlinOutput,
                resourcesOutput = resourcesOutput,
                packageName = "com.example.generated",
                className = "GeneratedGatewayProtocol",
                descriptorSet = descriptorSet,
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

    @Test
    fun `splits large gateway protocol mappings into chunk files`() {
        val files = ProtobufGatewayProtocolGenerator.buildFiles(
            config = ProtobufGatewayGeneratorConfig(
                metadata = Path("metadata.json"),
                kotlinOutput = Path("build/generated"),
                resourcesOutput = Path("build/resources"),
                packageName = "com.example.generated",
                className = "GeneratedGatewayProtocol",
            ),
            messages = (0..200).map { index ->
                GatewayMessageSpec(
                    id = 1000 + index,
                    type = "com.example.protocol.Message$index",
                    direction = GatewayMessageDirection.SERVER,
                )
            },
        )

        val fileNames = files.map { it.fileName }
        val aggregator = files.first { it.fileName == "GeneratedGatewayProtocol" }.file.toString()
        val chunk0 = files.first { it.fileName == "GeneratedGatewayProtocolChunk0" }.file.toString()
        val chunk1 = files.first { it.fileName == "GeneratedGatewayProtocolChunk1" }.file.toString()

        assertContains(fileNames, "GeneratedGatewayProtocol")
        assertContains(fileNames, "GeneratedGatewayProtocolChunk0")
        assertContains(fileNames, "GeneratedGatewayProtocolChunk1")
        assertContains(aggregator, "GeneratedGatewayProtocolChunk0.contribute(builder)")
        assertContains(aggregator, "GeneratedGatewayProtocolChunk1.contribute(builder)")
        assertContains(chunk0, "internal object GeneratedGatewayProtocolChunk0")
        assertContains(chunk1, "internal object GeneratedGatewayProtocolChunk1")
    }

    private fun testDescriptorSet(): DescriptorProtos.FileDescriptorSet {
        val loginReq = DescriptorProtos.DescriptorProto.newBuilder()
            .setName("LoginReq")
            .addField(
                DescriptorProtos.FieldDescriptorProto.newBuilder()
                    .setName("player_id")
                    .setNumber(1)
                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64),
            )
        val loginResp = DescriptorProtos.DescriptorProto.newBuilder()
            .setName("LoginResp")
        val ping = DescriptorProtos.DescriptorProto.newBuilder()
            .setName("Ping")
        val file = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("game_protocol.proto")
            .setPackage("com.example.protocol")
            .setOptions(
                DescriptorProtos.FileOptions.newBuilder()
                    .setJavaPackage("com.example.protocol")
                    .setJavaMultipleFiles(true),
            )
            .addMessageType(loginReq)
            .addMessageType(loginResp)
            .addMessageType(ping)
        return DescriptorProtos.FileDescriptorSet.newBuilder()
            .addFile(file)
            .build()
    }
}
