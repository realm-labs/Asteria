plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":persistence:persistence-core"))
    api(project(":persistence:persistence-mongodb-annotations"))
    api(project(":observability:observability-core"))
    api(libs.mongodb.driver.kotlin.coroutine)
    implementation(libs.slf4j.api)
    testImplementation(libs.testcontainers.mongodb)
}
