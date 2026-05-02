plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-core"))
    api(project(":asteria-config"))
    api(project(":asteria-config-center"))
    api(libs.typesafe.config)
}
