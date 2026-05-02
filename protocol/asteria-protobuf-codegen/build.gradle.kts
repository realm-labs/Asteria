plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    implementation(project(":asteria-protocol-protobuf"))
    implementation(project(":asteria-rpc-protobuf"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinpoet)
    implementation(libs.protobuf.kotlin)
}
