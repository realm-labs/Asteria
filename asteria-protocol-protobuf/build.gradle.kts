plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-message"))
    api(libs.protobuf.kotlin)
}
