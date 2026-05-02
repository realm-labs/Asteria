plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-message"))
    api(libs.kotlinx.coroutines.core)
}
