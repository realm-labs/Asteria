plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":config:config-core"))
    api(project(":config:config-center"))
    api(project(":config:config-luban"))
    implementation(libs.kotlinx.coroutines.core)
}
