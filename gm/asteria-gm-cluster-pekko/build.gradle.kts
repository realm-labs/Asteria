plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gm-cluster"))
    api(project(":asteria-cluster-pekko"))
}
