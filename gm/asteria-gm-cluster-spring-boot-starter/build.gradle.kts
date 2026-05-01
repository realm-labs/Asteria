plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gm-cluster"))
    api(project(":asteria-gm-spring-boot-starter"))
}
