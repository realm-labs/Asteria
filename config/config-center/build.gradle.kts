plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    api(project(":config:config-core"))
    api(project(":observability:observability-core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)
}
