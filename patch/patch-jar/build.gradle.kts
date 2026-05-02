plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":patch:patch-core"))
    api(project(":observability:observability-core"))
    implementation(libs.slf4j.api)
}
