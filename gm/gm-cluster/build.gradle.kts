plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gm:gm-core"))
    api(project(":cluster:cluster-config"))
}
