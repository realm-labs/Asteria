package io.github.realmlabs.asteria.rpc.protobuf.generator

import com.google.protobuf.DescriptorProtos
import io.github.realmlabs.asteria.rpc.protobuf.AsteriaRpcOptionsProto
import io.github.realmlabs.asteria.rpc.protobuf.ProtobufRpcProtocolContributor
import kotlin.io.path.*
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
                },
                {
                  "id": 9002,
                  "type": "com.example.protocol.KickPlayerReq"
                },
                {
                  "id": 9003,
                  "type": "com.example.protocol.KickPlayerResp"
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
        assertContains(generatedCode, "builder.message(id = 9_002")
        assertContains(generatedCode, "messageClass = KickPlayerReq::class")
        assertContains(generatedCode, "builder.message(id = 9_003")
        assertContains(generatedCode, "messageClass = KickPlayerResp::class")

        val contributorFile = resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .resolve(ProtobufRpcProtocolContributor::class.qualifiedName!!)
        assertTrue(contributorFile.exists())
        assertContains(contributorFile.readText(), "com.example.generated.GeneratedRpcProtocol")
    }

    @Test
    fun `generates protobuf rpc entity id resolvers from message options`() {
        val workDir = createTempDirectory("asteria-rpc-protocol-entity-id-generator")
        val metadata = workDir.resolve("rpc-protocol.json")
        val descriptorSet = workDir.resolve("descriptors.pb")
        val kotlinOutput = workDir.resolve("kotlin")
        val resourcesOutput = workDir.resolve("resources")
        metadata.writeText(
            """
            {
              "messages": [
                {
                  "id": 9001,
                  "type": "com.example.protocol.ProtoLogin.LoginReq"
                }
              ]
            }
            """.trimIndent(),
        )
        descriptorSet.outputStream().use { testDescriptorSet().writeTo(it) }

        ProtobufRpcProtocolGenerator.generate(
            ProtobufRpcGeneratorConfig(
                metadata = metadata,
                descriptorSet = descriptorSet,
                kotlinOutput = kotlinOutput,
                resourcesOutput = resourcesOutput,
                packageName = "com.example.generated",
                className = "GeneratedRpcProtocol",
            ),
        )

        val generatedFile = kotlinOutput.resolve(Path("com/example/generated/GeneratedRpcProtocol.kt"))
        assertTrue(generatedFile.exists())
        val generatedCode = generatedFile.readText()
        assertContains(generatedCode, "builder.message(id = 9_001")
        assertContains(generatedCode, "builder.entityId<ProtoLogin.LoginReq>")
        assertContains(generatedCode, "message.playerId.toString()")
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
            .setOptions(
                DescriptorProtos.MessageOptions.newBuilder()
                    .setExtension(AsteriaRpcOptionsProto.entityId, "player_id"),
            )
        val file = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("proto_login.proto")
            .setPackage("game.rpc")
            .setOptions(
                DescriptorProtos.FileOptions.newBuilder()
                    .setJavaPackage("com.example.protocol")
                    .setJavaOuterClassname("ProtoLogin"),
            )
            .addMessageType(loginReq)
        return DescriptorProtos.FileDescriptorSet.newBuilder()
            .addFile(file)
            .build()
    }
}
