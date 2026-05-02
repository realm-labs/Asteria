plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":patch:patch-core"))
    api(project(":observability:observability-core"))
    api(libs.mongodb.driver.kotlin.coroutine)
    api(libs.mongodb.driver.sync)
    implementation(libs.slf4j.api)
}
