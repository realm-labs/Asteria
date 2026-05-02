plugins {
    id("asteria.kotlin-library-conventions")
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":protocol:protocol-protobuf"))
    implementation(project(":protocol:protobuf-codegen"))
    implementation(project(":rpc:rpc-protobuf"))
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.3.21")
    implementation(localGroovy())

    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("asteriaProtobufProtocolCodegen") {
            id = "io.github.mikai233.asteria.protobuf-protocol-codegen"
            implementationClass = "io.github.mikai233.asteria.protocol.protobuf.gradle.AsteriaProtobufProtocolCodegenPlugin"
        }
    }
}

tasks.jar {
    manifest {
        attributes["Implementation-Version"] = project.version.toString()
    }
}
