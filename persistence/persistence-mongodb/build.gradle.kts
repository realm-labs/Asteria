plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":persistence:persistence-core"))
    api(libs.mongodb.driver.kotlin.coroutine)
}
