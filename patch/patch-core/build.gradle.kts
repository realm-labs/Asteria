plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    api(project(":foundation:foundation-message"))
    api(project(":observability:observability-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
