plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gm-core"))
    api(libs.kotlinx.coroutines.reactor)
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.web)
    api(libs.jakarta.servlet.api)
}
