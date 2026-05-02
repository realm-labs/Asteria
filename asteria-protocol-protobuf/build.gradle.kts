plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gateway-core"))
    api(project(":asteria-message"))
    api(project(":asteria-protobuf"))
    api(libs.protobuf.kotlin)
}
