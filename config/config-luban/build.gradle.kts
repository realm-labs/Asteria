plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":config:config-core"))

    implementation(libs.jackson.databind)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.core)
}
