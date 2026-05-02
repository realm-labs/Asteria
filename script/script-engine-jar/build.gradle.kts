plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":script:script-core"))
    api(project(":observability:observability-core"))
    implementation(libs.kotlin.reflect)
    implementation(libs.slf4j.api)
}
