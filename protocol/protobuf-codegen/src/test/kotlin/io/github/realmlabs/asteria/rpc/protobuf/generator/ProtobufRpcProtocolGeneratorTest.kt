package io.github.realmlabs.asteria.rpc.protobuf.generator

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
}
