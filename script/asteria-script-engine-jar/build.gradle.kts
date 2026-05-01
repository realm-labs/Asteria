plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-script-core"))
    implementation(libs.kotlin.reflect)
}
