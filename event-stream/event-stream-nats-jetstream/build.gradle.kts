plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":event-stream:event-stream-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.nats.client)
    implementation(libs.slf4j.api)
}
