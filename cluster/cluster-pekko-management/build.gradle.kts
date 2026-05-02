plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":cluster:cluster-pekko"))
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.pekko.discovery)
    implementation(libs.pekko.management)
    implementation(libs.pekko.management.cluster.bootstrap)
    implementation(libs.pekko.management.cluster.http)
}
