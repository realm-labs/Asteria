plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-id"))
    api(libs.jetcd.core)

    implementation(libs.kotlinx.coroutines.jdk8)
}
