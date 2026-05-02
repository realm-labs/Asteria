plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-core"))
    api(project(":asteria-actor"))
    api(project(":asteria-message"))
    api(project(":asteria-observability-core"))
    api(project(":asteria-rpc"))
    api(project(":asteria-rpc-protobuf"))
    api(project(":asteria-cluster-pekko"))
    api(project(":asteria-cluster-config"))
    api(project(":asteria-protocol-protobuf"))
    api(project(":asteria-gateway-netty"))
    api(project(":asteria-config"))
}
