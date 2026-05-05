plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    implementation(project(":foundation:foundation-actor"))
    api(project(":foundation:foundation-message"))
    api(project(":cluster:cluster-config"))
    implementation(project(":config:config-core"))
    api(libs.pekko.actor)
    api(libs.pekko.cluster.sharding)
    api(libs.pekko.cluster.tools)
    api(libs.typesafe.config)
    implementation(libs.kotlinx.coroutines.jdk8)
    testImplementation(libs.pekko.testkit)
}
