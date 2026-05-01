plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-config"))

    implementation(libs.jackson.databind)
}
