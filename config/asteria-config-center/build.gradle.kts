plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-core"))
    api(project(":asteria-config"))
    api(libs.kotlinx.coroutines.core)
    api(libs.jackson.databind)
    api(libs.jackson.module.kotlin)
}
