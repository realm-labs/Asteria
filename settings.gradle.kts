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
    "asteria-protocol-protobuf",
    "asteria-gateway-netty",
    "asteria-persistence",
    "asteria-config",
    "asteria-config-luban",
    "asteria-starter",
)
