plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gm:gm-config"))
    api(project(":cluster:cluster-config"))
    api(project(":gm:gm-spring-boot-starter"))
}
