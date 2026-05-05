plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    api(project(":config:config-core"))
    api(project(":config:config-center"))
    api(libs.kotlinx.coroutines.core)
    api(libs.typesafe.config)
    testImplementation(libs.kotlinx.coroutines.core)
}
