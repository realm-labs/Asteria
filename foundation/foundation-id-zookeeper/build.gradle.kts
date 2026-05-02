plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-id"))
    api(libs.curator.x.async)

    implementation(libs.curator.framework)
    implementation(libs.kotlinx.coroutines.jdk8)

    testImplementation(libs.curator.test)
}
