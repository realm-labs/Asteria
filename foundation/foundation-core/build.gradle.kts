plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
