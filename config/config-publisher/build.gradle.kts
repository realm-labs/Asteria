plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":config:config-core"))
    api(project(":config:config-center"))
    api(project(":config:config-luban"))
    api(project(":observability:observability-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
