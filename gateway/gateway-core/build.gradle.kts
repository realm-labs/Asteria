plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-message"))
    implementation(libs.slf4j.api)
    testImplementation(libs.kotlinx.coroutines.core)
}
