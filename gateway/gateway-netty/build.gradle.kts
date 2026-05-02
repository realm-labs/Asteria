plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gateway:gateway-core"))
    api(project(":foundation:foundation-core"))
    api(project(":foundation:foundation-message"))
    api(project(":observability:observability-core"))
    api(project(":protocol:protocol-protobuf"))
    api(libs.netty.all)
    implementation(libs.slf4j.api)
}
