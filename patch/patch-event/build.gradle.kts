plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":patch:patch-core"))
    api(project(":foundation:foundation-event"))
    testImplementation(libs.kotlinx.coroutines.core)
}
