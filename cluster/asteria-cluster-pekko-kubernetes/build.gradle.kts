plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-cluster-pekko-management"))
    implementation(libs.pekko.discovery.kubernetes.api)
}
