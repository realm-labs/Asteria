plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-core"))
    api(project(":asteria-message"))
    api(project(":asteria-protocol-protobuf"))
    api(libs.netty.all)
}
