plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gm-config"))
    api(project(":asteria-cluster-config"))
    api(project(":asteria-gm-spring-boot-starter"))
}
