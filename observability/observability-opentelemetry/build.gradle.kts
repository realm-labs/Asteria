plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":observability:observability-core"))
    api(libs.opentelemetry.api)
    implementation(libs.opentelemetry.extension.kotlin)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.kotlinx.coroutines.core)
}
