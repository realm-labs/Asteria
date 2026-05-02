plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    implementation(project(":protocol:protocol-protobuf"))
    implementation(project(":rpc:rpc-protobuf"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinpoet)
    implementation(libs.protobuf.kotlin)
}
