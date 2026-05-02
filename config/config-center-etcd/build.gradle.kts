plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":config:config-center"))

    implementation(libs.jetcd.core)
    implementation(libs.kotlinx.coroutines.jdk8)
}
