plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gateway-core"))
    api(project(":asteria-cluster-pekko"))
    testImplementation(libs.pekko.testkit)
}
