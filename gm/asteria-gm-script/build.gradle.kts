plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gm-core"))
    api(project(":asteria-script-core"))
    api(project(":asteria-script-job"))
}
