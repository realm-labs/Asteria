plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gateway:gateway-core"))
    api(project(":cluster:cluster-pekko"))
    api(project(":observability:observability-core"))
    implementation(libs.slf4j.api)
    testImplementation(libs.pekko.testkit)
}
