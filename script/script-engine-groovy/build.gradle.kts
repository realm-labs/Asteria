plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":script:script-core"))
    implementation(libs.groovy)
    implementation(libs.kotlin.reflect)
}
