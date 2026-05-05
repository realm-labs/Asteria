plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":observability:observability-core"))
    api(project(":script:script-core"))
    api(project(":script:script-protobuf"))
    api(project(":foundation:foundation-actor"))
    api(project(":cluster:cluster-pekko"))
    implementation(libs.kotlinx.coroutines.jdk8)
    testImplementation(libs.kotlinx.coroutines.core)
}
