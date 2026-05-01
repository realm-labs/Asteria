plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-script-job-mongodb"))
    api(libs.spring.boot.autoconfigure)
    implementation(libs.kotlinx.coroutines.core)
}
