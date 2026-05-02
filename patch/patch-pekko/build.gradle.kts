plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":patch:patch-core"))
    api(project(":cluster:cluster-config"))
    api(libs.pekko.actor)
    api(libs.pekko.cluster.tools)
    implementation(libs.kotlinx.coroutines.jdk8)
}
