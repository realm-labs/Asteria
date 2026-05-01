plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-config-center"))

    implementation(libs.nacos.client)
}
