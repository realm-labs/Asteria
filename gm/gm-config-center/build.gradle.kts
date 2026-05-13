plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":config:config-center"))
    api(project(":gm:gm-core"))
    testImplementation(libs.kotlinx.coroutines.core)
}
