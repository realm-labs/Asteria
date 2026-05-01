plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-gm-cluster-pekko-management"))
    api(project(":asteria-gm-cluster-spring-boot-starter"))
    implementation(libs.spring.boot.autoconfigure)
}
