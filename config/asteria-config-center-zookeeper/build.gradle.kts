plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-config-center"))

    implementation(libs.curator.framework)
    implementation(libs.curator.recipes)
    implementation(libs.curator.x.async)
    implementation(libs.kotlinx.coroutines.jdk8)

    testImplementation(libs.curator.test)
}
