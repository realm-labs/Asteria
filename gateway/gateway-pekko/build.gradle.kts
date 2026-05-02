plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gateway:gateway-core"))
    api(project(":cluster:cluster-pekko"))
    testImplementation(libs.pekko.testkit)
}
