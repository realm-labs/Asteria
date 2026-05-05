plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gm:gm-core"))
    api(project(":observability:observability-core"))
    implementation(libs.kotlinx.coroutines.reactor)
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.web)
    api(libs.jakarta.servlet.api)

    implementation(libs.slf4j.api)
}
