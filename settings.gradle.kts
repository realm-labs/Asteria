plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "Asteria"

include(
    "asteria-core",
    "asteria-actor",
    "asteria-message",
    "asteria-cluster-pekko",
    "asteria-protocol-protobuf",
    "asteria-gateway-netty",
    "asteria-persistence",
    "asteria-config",
    "asteria-starter",
)
