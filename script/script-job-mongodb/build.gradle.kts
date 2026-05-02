plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":script:script-job"))
    api(libs.mongodb.driver.kotlin.coroutine)
}
