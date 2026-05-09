plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":patch:patch-core"))

    implementation(project(":observability:observability-core"))
    implementation(libs.curator.x.async)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.slf4j.api)

    testImplementation(libs.curator.test)
}
