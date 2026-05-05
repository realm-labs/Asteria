plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-actor"))
    api(project(":foundation:foundation-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.reflect)
    testImplementation(libs.kotlinx.coroutines.core)
}
