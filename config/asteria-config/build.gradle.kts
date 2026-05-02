plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-core"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.core)
}
