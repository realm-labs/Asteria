plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-id"))
    api(project(":observability:observability-core"))
    api(libs.jetcd.core)

    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.slf4j.api)
}
