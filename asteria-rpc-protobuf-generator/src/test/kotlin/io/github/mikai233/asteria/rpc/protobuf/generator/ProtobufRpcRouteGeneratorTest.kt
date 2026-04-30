package io.github.mikai233.asteria.rpc.protobuf.generator

import com.google.protobuf.DescriptorProtos
import io.github.mikai233.asteria.rpc.RpcRouteRegistryProvider
import io.github.mikai233.asteria.rpc.protobuf.AsteriaRpcOptionsProto
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ProtobufRpcRouteGeneratorTest {
    @Test
    fun generatesRoutesFromMessageOptions() {
        val workDir = createTempDirectory("asteria-rpc-generator")
        val descriptorSetPath = workDir.resolve("routes.pb")
        val kotlinOutput = workDir.resolve("kotlin")
        val resourcesOutput = workDir.resolve("resources")

        descriptorSetPath.writeBytes(testDescriptorSet().toByteArray())

        ProtobufRpcRouteGenerator.generate(
            GeneratorConfig(
                descriptorSet = descriptorSetPath,
                kotlinOutput = kotlinOutput,
                resourcesOutput = resourcesOutput,
                packageName = "com.example.generated",
                className = "GeneratedRoutes",
            ),
        )

        val generatedFile = kotlinOutput.resolve(Path("com/example/generated/GeneratedRoutes.kt"))
        assertTrue(generatedFile.exists())
        val generatedCode = generatedFile.readText()
        assertContains(generatedCode, "object GeneratedRoutes")
        assertContains(generatedCode, "route<ProtoLogin.LoginReq>")
        assertContains(generatedCode, "RpcTarget.Entity(EntityKind(\"player\"), message.playerId.toString())")

        val serviceFile = resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .resolve(RpcRouteRegistryProvider::class.qualifiedName!!)
        assertTrue(serviceFile.exists())
        assertContains(serviceFile.readText(), "com.example.generated.GeneratedRoutes")
    }

    private fun testDescriptorSet(): DescriptorProtos.FileDescriptorSet {
        val route = AsteriaRpcOptionsProto.RpcRoute.newBuilder()
            .setEntity(
                AsteriaRpcOptionsProto.EntityRpcRoute.newBuilder()
                    .setKind("player")
                    .setIdField("player_id"),
            )
            .build()
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
                    .setExtension(AsteriaRpcOptionsProto.rpcRoute, route),
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
