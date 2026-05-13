plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gm:gm-spring-boot-starter"))
    api(project(":gm:gm-script-spring-boot-starter"))
    api(project(":gm:gm-config-spring-boot-starter"))
    api(project(":gm:gm-config-center-spring-boot-starter"))
    api(project(":gm:gm-cluster-spring-boot-starter"))
    api(project(":gm:gm-patch-spring-boot-starter"))
}
