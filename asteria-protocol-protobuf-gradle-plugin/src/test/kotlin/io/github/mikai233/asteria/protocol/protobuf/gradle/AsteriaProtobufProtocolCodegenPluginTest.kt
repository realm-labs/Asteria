package io.github.mikai233.asteria.protocol.protobuf.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsteriaProtobufProtocolCodegenPluginTest {
    @Test
    fun `generates protocol sources and client metadata`() {
        val projectDir = createTempDirectory("asteria-protobuf-protocol-plugin")
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    mavenCentral()
                }
            }
            rootProject.name = "protocol-plugin-test"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.3.21"
                id("io.github.mikai233.asteria.protobuf-protocol-codegen")
            }

            asteriaProtobufProtocol {
                addDependencies.set(false)
                packageName.set("com.example.generated")

                gateway {
                    enabled.set(true)
                    metadataFile.set(layout.projectDirectory.file("protocol/gateway.json"))
                    clientMetadataEnabled.set(true)
                    clientMetadataFile.set(layout.buildDirectory.file("client/gateway.json"))
                }

                rpc {
                    enabled.set(true)
                    metadataFile.set(layout.projectDirectory.file("protocol/rpc.json"))
                }
            }
            """.trimIndent(),
        )
        val protocolDir = projectDir.resolve("protocol").also { it.createDirectories() }
        protocolDir.resolve("gateway.json").writeText(
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
        protocolDir.resolve("rpc.json").writeText(
            """
            {
              "methods": [
                {
                  "id": 2001,
                  "name": "player.query",
                  "mode": "TELL",
                  "requestType": "com.example.protocol.QueryPlayerReq",
                  "target": { "type": "ENTITY", "name": "player" },
                  "entityIdProperty": "playerId"
                }
              ]
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("generateAsteriaGatewayProtocol", "generateAsteriaRpcProtocol", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateAsteriaGatewayProtocol")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateAsteriaRpcProtocol")?.outcome)
        val gatewaySource = projectDir.resolve(
            Path("build/generated/asteria/protobufProtocol/kotlin/gateway/com/example/generated/gateway/GeneratedGatewayProtocol.kt"),
        )
        val rpcSource = projectDir.resolve(
            Path("build/generated/asteria/protobufProtocol/kotlin/rpc/com/example/generated/rpc/GeneratedRpcProtocol.kt"),
        )
        val gatewayClientMetadata = projectDir.resolve(Path("build/client/gateway.json"))
        assertTrue(gatewaySource.exists())
        assertTrue(rpcSource.exists())
        assertTrue(gatewayClientMetadata.exists())
        assertContains(gatewaySource.readText(), "class GeneratedGatewayProtocol : GeneratedProtobufGatewayProtocol()")
        assertContains(rpcSource.readText(), "class GeneratedRpcProtocol : GeneratedProtobufRpcProtocol()")
        assertContains(gatewayClientMetadata.readText(), "\"direction\": \"C2S\"")
        assertContains(gatewayClientMetadata.readText(), "\"responseTo\": \"login\"")
    }
}
