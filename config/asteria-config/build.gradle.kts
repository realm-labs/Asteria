plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-core"))

    testImplementation(libs.kotlinx.coroutines.core)
}
