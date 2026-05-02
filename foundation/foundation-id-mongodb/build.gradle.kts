plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-id"))
    api(libs.mongodb.driver.kotlin.coroutine)
}
