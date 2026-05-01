plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-script-job"))
    api(libs.mongodb.driver.kotlin.coroutine)
}
