plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    implementation(project(":cluster:cluster-pekko"))
    implementation(project(":patch:patch-core"))
    api(project(":script:script-control-api"))
    implementation(project(":script:script-core"))
    implementation(project(":script:script-job"))
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.slf4j.api)
}
