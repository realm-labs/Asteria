plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))

    testImplementation(libs.kotlinx.coroutines.core)
}
