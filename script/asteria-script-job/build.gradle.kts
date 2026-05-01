plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-core"))
    api(project(":asteria-observability-core"))
    api(project(":asteria-script-core"))
}
