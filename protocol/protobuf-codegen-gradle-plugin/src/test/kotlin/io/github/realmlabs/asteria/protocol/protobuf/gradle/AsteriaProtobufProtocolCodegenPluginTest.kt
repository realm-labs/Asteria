package io.github.realmlabs.asteria.protocol.protobuf.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import kotlin.io.path.*
import kotlin.test.*

class AsteriaProtobufProtocolCodegenPluginTest {
    @Test
    fun `adds asteria protobuf dependencies with published group`() {
        val projectDir = createTempDirectory("asteria-protobuf-protocol-dependencies")
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
            rootProject.name = "protocol-plugin-dependencies-test"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.3.21"
                id("io.github.realm-labs.asteria.protobuf-protocol-codegen")
            }

            asteriaProtobufProtocol {
                asteriaVersion.set("0.1.2")
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("dependencies", "--configuration", "implementation", "--stacktrace")
            .build()

        assertContains(result.output, "io.github.realm-labs.asteria:protocol-protobuf:0.1.2")
        assertContains(result.output, "io.github.realm-labs.asteria:rpc-protobuf:0.1.2")
    }

    @Test
    fun `codegen tasks depend on protobuf generateProto task`() {
        listOf("generateAsteriaGatewayProtocol", "generateAsteriaRpcProtocol").forEach { taskName ->
            val projectDir = createTempDirectory("asteria-protobuf-protocol-generate-proto")
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
                rootProject.name = "protocol-plugin-generate-proto-test"
                """.trimIndent(),
            )
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    kotlin("jvm") version "2.3.21"
                    id("com.google.protobuf") version "0.10.0"
                    id("io.github.realm-labs.asteria.protobuf-protocol-codegen")
                }

                asteriaProtobufProtocol {
                    addDependencies.set(false)

                    gateway {
                        enabled.set(true)
                        metadataFile.set(layout.projectDirectory.file("protocol/gateway.json"))
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
                      "type": "test.TestMessage",
                      "direction": "C2S",
                      "target": { "type": "ENTITY", "name": "test" }
                    }
                  ],
                  "routes": []
                }
                """.trimIndent(),
            )
            protocolDir.resolve("rpc.json").writeText(
                """
                {
                  "messages": [
                    {
                      "id": 2001,
                      "type": "test.TestMessage"
                    }
                  ]
                }
                """.trimIndent(),
            )
            projectDir.resolve("src/main/proto").createDirectories()
            projectDir.resolve("src/main/proto/test.proto").writeText(
                """
                syntax = "proto3";

                package test;

                message TestMessage {
                  string id = 1;
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(taskName, "--dry-run", "--stacktrace")
                .build()

            assertContains(result.output, ":generateProto SKIPPED")
            assertContains(result.output, ":$taskName SKIPPED")
        }
    }

    @Test
    fun `registers gateway and rpc output directories as kotlin source roots`() {
        val projectDir = createTempDirectory("asteria-protobuf-protocol-source-roots")
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
            rootProject.name = "protocol-plugin-source-roots-test"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.3.21"
                id("io.github.realm-labs.asteria.protobuf-protocol-codegen")
            }

            asteriaProtobufProtocol {
                addDependencies.set(false)
            }

            tasks.register("printKotlinSourceRoots") {
                doLast {
                    val extension = project.extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
                    extension.sourceSets.getByName("main").kotlin.srcDirs
                        .map { projectDir.toPath().relativize(it.toPath()).toString().replace('\\', '/') }
                        .sorted()
                        .forEach { println("KOTLIN_SRC=${'$'}it") }
                }
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("printKotlinSourceRoots", "--stacktrace")
            .build()

        assertContains(
            result.output,
            "KOTLIN_SRC=build/generated/asteria/protobufProtocol/kotlin/gateway",
        )
        assertContains(
            result.output,
            "KOTLIN_SRC=build/generated/asteria/protobufProtocol/kotlin/rpc",
        )
        assertFalse(
            result.output.lines().any { it == "KOTLIN_SRC=build/generated/asteria/protobufProtocol/kotlin" },
        )
    }

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
                id("io.github.realm-labs.asteria.protobuf-protocol-codegen")
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
              "messages": [
                {
                  "id": 2001,
                  "type": "com.example.protocol.QueryPlayerReq"
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
        val gatewayContributor = projectDir.resolve(
            Path("build/generated/asteria/protobufProtocol/resources/META-INF/services/io.github.realmlabs.asteria.protocol.protobuf.ProtobufGatewayProtocolContributor"),
        )
        val rpcContributor = projectDir.resolve(
            Path("build/generated/asteria/protobufProtocol/resources/META-INF/services/io.github.realmlabs.asteria.rpc.protobuf.ProtobufRpcProtocolContributor"),
        )
        val gatewayClientMetadata = projectDir.resolve(Path("build/client/gateway.json"))
        assertTrue(gatewaySource.exists())
        assertTrue(rpcSource.exists())
        assertTrue(gatewayContributor.exists())
        assertTrue(rpcContributor.exists())
        assertTrue(gatewayClientMetadata.exists())
        assertContains(gatewaySource.readText(), "class GeneratedGatewayProtocol : GeneratedProtobufGatewayProtocol()")
        assertContains(rpcSource.readText(), "class GeneratedRpcProtocol : GeneratedProtobufRpcProtocol()")
        assertContains(gatewayContributor.readText(), "com.example.generated.gateway.GeneratedGatewayProtocol")
        assertContains(rpcContributor.readText(), "com.example.generated.rpc.GeneratedRpcProtocol")
        assertContains(gatewayClientMetadata.readText(), "\"direction\": \"C2S\"")
        assertContains(gatewayClientMetadata.readText(), "\"responseTo\": \"login\"")
    }
}
