plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gm:gm-cluster"))
    api(project(":cluster:cluster-pekko"))
}
