plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":config:config-center"))

    implementation(libs.nacos.client)
}
