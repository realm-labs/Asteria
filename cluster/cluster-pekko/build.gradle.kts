plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    api(project(":foundation:foundation-message"))
    api(project(":cluster:cluster-config"))
    api(libs.pekko.actor)
    api(libs.pekko.cluster.sharding)
    api(libs.pekko.cluster.tools)
    api(libs.typesafe.config)
    implementation(libs.kotlinx.coroutines.jdk8)
}
