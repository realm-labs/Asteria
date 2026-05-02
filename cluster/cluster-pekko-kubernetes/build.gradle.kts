plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":cluster:cluster-pekko-management"))
    implementation(libs.pekko.discovery.kubernetes.api)
}
