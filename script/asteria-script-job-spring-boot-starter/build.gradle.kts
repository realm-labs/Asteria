plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-script-job"))
    api(libs.spring.boot.autoconfigure)
    implementation(libs.kotlinx.coroutines.core)
}
