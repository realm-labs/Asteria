plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":script:script-job"))
    api(libs.spring.boot.autoconfigure)
    implementation(libs.kotlinx.coroutines.core)
}
