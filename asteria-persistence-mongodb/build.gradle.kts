plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-persistence"))
    api(libs.mongodb.driver.kotlin.coroutine)
}
