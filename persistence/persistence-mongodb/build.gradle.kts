plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":persistence:persistence-core"))
    api(project(":persistence:persistence-mongodb-annotations"))
    api(project(":observability:observability-core"))
    api(libs.mongodb.driver.kotlin.coroutine)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.jqwik)
    testImplementation(libs.testcontainers.mongodb)
}
