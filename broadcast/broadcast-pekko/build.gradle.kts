plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":broadcast:broadcast-core"))
    api(project(":cluster:cluster-pekko"))
    api(libs.pekko.cluster.tools)
    implementation(libs.slf4j.api)
}
