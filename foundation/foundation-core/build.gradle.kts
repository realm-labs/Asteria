plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
