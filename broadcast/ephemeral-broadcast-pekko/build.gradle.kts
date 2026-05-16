plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":broadcast:ephemeral-broadcast-core"))
    api(project(":cluster:cluster-pekko"))
    implementation(libs.pekko.cluster.tools)
    implementation(libs.slf4j.api)
}
