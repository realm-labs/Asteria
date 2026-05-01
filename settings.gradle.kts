plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "Asteria"

include(
    "asteria-core",
    "asteria-actor",
    "asteria-message",
    "asteria-observability-core",
    "asteria-observability-opentelemetry",
    "asteria-rpc",
    "asteria-rpc-protobuf",
    "asteria-rpc-protobuf-generator",
    "asteria-script-core",
    "asteria-script-job",
    "asteria-script-protobuf",
    "asteria-script-pekko",
    "asteria-script-engine-jar",
    "asteria-script-engine-groovy",
    "asteria-cluster-pekko",
    "asteria-cluster-pekko-management",
    "asteria-cluster-pekko-kubernetes",
    "asteria-protocol-protobuf",
    "asteria-gateway-netty",
    "asteria-persistence",
    "asteria-config",
    "asteria-config-luban",
    "asteria-config-center",
    "asteria-config-center-zookeeper",
    "asteria-config-center-etcd",
    "asteria-config-center-nacos",
    "asteria-cluster-config",
    "asteria-starter",
)

mapOf(
    "asteria-core" to "foundation/asteria-core",
    "asteria-actor" to "foundation/asteria-actor",
    "asteria-message" to "foundation/asteria-message",
    "asteria-observability-core" to "observability/asteria-observability-core",
    "asteria-observability-opentelemetry" to "observability/asteria-observability-opentelemetry",
    "asteria-rpc" to "rpc/asteria-rpc",
    "asteria-rpc-protobuf" to "rpc/asteria-rpc-protobuf",
    "asteria-rpc-protobuf-generator" to "rpc/asteria-rpc-protobuf-generator",
    "asteria-script-core" to "script/asteria-script-core",
    "asteria-script-job" to "script/asteria-script-job",
    "asteria-script-protobuf" to "script/asteria-script-protobuf",
    "asteria-script-pekko" to "script/asteria-script-pekko",
    "asteria-script-engine-jar" to "script/asteria-script-engine-jar",
    "asteria-script-engine-groovy" to "script/asteria-script-engine-groovy",
    "asteria-config" to "config/asteria-config",
    "asteria-config-luban" to "config/asteria-config-luban",
    "asteria-config-center" to "config/asteria-config-center",
    "asteria-config-center-zookeeper" to "config/asteria-config-center-zookeeper",
    "asteria-config-center-etcd" to "config/asteria-config-center-etcd",
    "asteria-config-center-nacos" to "config/asteria-config-center-nacos",
    "asteria-cluster-config" to "config/asteria-cluster-config",
).forEach { (name, path) ->
    project(":$name").projectDir = file(path)
}
