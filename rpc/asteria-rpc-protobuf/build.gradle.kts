import com.google.protobuf.gradle.id

plugins {
    id("asteria.kotlin-library-conventions")
    alias(libs.plugins.protobuf)
}

dependencies {
    api(project(":asteria-rpc"))
    api(project(":asteria-protobuf"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach {
            it.builtins {
                id("kotlin")
            }
        }
    }
}
