plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":event-stream:event-stream-core"))
    api(project(":foundation:foundation-protobuf"))
    testImplementation(libs.kotlinx.coroutines.core)
}
