plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-id"))
    api(project(":observability:observability-core"))
    api(libs.mongodb.driver.kotlin.coroutine)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
