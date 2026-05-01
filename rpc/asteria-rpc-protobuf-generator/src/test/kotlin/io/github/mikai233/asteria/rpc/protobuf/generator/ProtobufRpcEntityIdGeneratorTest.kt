package io.github.mikai233.asteria.rpc.protobuf.generator

import com.google.protobuf.DescriptorProtos
import io.github.mikai233.asteria.rpc.RpcEntityIdRegistryProvider
import io.github.mikai233.asteria.rpc.protobuf.AsteriaRpcOptionsProto
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ProtobufRpcEntityIdGeneratorTest {
    @Test
    fun generatesEntityIdsFromMessageOptions() {
        val workDir = createTempDirectory("asteria-rpc-generator")
        val descriptorSetPath = workDir.resolve("entity-ids.pb")
        val kotlinOutput = workDir.resolve("kotlin")
        val resourcesOutput = workDir.resolve("resources")

        descriptorSetPath.writeBytes(testDescriptorSet().toByteArray())

        ProtobufRpcEntityIdGenerator.generate(
            GeneratorConfig(
                descriptorSet = descriptorSetPath,
                kotlinOutput = kotlinOutput,
                resourcesOutput = resourcesOutput,
                packageName = "com.example.generated",
                className = "GeneratedEntityIds",
            ),
        )

        val generatedFile = kotlinOutput.resolve(Path("com/example/generated/GeneratedEntityIds.kt"))
        assertTrue(generatedFile.exists())
        val generatedCode = generatedFile.readText()
        assertContains(generatedCode, "object GeneratedEntityIds")
        assertContains(generatedCode, "entityId<ProtoLogin.LoginReq>")
        assertContains(generatedCode, "message.playerId.toString()")

        val serviceFile = resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .resolve(RpcEntityIdRegistryProvider::class.qualifiedName!!)
        assertTrue(serviceFile.exists())
        assertContains(serviceFile.readText(), "com.example.generated.GeneratedEntityIds")
    }

    private fun testDescriptorSet(): DescriptorProtos.FileDescriptorSet {
        val message = DescriptorProtos.DescriptorProto.newBuilder()
            .setName("LoginReq")
            .addField(
                DescriptorProtos.FieldDescriptorProto.newBuilder()
                    .setName("player_id")
                    .setNumber(1)
                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64),
            )
            .setOptions(
                DescriptorProtos.MessageOptions.newBuilder()
                    .setExtension(AsteriaRpcOptionsProto.rpcEntityIdField, "player_id"),
            )
        val file = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("proto_login.proto")
            .setPackage("com.example.protocol")
            .setOptions(
                DescriptorProtos.FileOptions.newBuilder()
                    .setJavaPackage("com.example.protocol")
                    .setJavaOuterClassname("ProtoLogin"),
            )
            .addMessageType(message)
        return DescriptorProtos.FileDescriptorSet.newBuilder()
            .addFile(file)
            .build()
    }
}
