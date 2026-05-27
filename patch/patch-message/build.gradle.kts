plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":patch:patch-core"))
    api(project(":foundation:foundation-message"))
    testImplementation(libs.kotlinx.coroutines.core)
}
