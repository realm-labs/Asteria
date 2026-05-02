plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":config:config-core"))
    api(project(":config:config-center"))
    implementation(libs.kotlinx.coroutines.core)
}
