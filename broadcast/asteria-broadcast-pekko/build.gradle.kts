plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-broadcast"))
    api(project(":asteria-cluster-pekko"))
    api(libs.pekko.cluster.tools)
}
