plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gateway:gateway-core"))
    api(project(":foundation:foundation-message"))
    api(project(":foundation:foundation-protobuf"))
    api(libs.protobuf.kotlin)
    testImplementation(libs.kotlinx.coroutines.core)
}
