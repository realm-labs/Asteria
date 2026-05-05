plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":config:config-center"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.nacos.client)
    testImplementation(libs.kotlinx.coroutines.core)
}
