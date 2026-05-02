plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    api(project(":config:config-core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.jackson.databind)
    api(libs.jackson.module.kotlin)
}
