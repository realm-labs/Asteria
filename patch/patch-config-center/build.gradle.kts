plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":patch:patch-core"))
    api(project(":config:config-center"))

    implementation(project(":observability:observability-core"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
