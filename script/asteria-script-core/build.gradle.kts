plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-actor"))
    api(project(":asteria-core"))
    implementation(libs.kotlin.reflect)
}
