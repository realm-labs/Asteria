plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-config-center"))

    implementation(libs.jetcd.core)
    implementation(libs.kotlinx.coroutines.jdk8)
}
