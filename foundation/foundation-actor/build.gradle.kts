plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    api(project(":observability:observability-core"))
    api(libs.pekko.actor)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.jdk8)
}
