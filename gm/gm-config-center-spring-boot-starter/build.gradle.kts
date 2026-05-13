plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gm:gm-config-center"))
    api(project(":gm:gm-spring-boot-starter"))
    api(libs.spring.boot.autoconfigure)
}
