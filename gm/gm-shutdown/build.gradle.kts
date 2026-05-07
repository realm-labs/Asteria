plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    api(project(":gm:gm-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
