plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gm-cluster"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.coroutines.core)
}
