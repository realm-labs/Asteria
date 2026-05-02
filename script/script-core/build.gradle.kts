plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-actor"))
    api(project(":foundation:foundation-core"))
    implementation(libs.kotlin.reflect)
}
