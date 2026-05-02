plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    implementation(project(":asteria-protocol-protobuf"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinpoet)
}
