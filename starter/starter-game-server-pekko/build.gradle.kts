plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    api(project(":foundation:foundation-actor"))
    api(project(":foundation:foundation-message"))
    api(project(":observability:observability-core"))
    api(project(":rpc:rpc-core"))
    api(project(":rpc:rpc-protobuf"))
    api(project(":cluster:cluster-pekko"))
    api(project(":cluster:cluster-config"))
    api(project(":protocol:protocol-protobuf"))
    api(project(":gateway:gateway-netty"))
    api(project(":config:config-core"))
    api(project(":config:config-center"))
}
