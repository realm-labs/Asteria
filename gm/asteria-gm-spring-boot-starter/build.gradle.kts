plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gm-core"))
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.web)
}
