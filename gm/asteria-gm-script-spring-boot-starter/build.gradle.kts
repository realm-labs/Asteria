plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gm-script"))
    api(project(":asteria-gm-spring-boot-starter"))
    api(project(":asteria-script-job-spring-boot-starter"))
}
