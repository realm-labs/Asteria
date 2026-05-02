plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gm:gm-cluster-pekko-management"))
    api(project(":gm:gm-cluster-spring-boot-starter"))
    implementation(libs.spring.boot.autoconfigure)
}
