plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gm:gm-patch"))
    api(project(":gm:gm-spring-boot-starter"))
}
