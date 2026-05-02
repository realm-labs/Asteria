plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":config:config-core"))

    implementation(libs.jackson.databind)
}
