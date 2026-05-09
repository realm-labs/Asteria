plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gm:gm-script"))
    api(project(":gm:gm-spring-boot-starter"))
    api(project(":script:script-control-api"))
    api(project(":script:script-job-spring-boot-starter"))
}
