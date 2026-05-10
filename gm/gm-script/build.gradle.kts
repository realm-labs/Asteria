plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":cluster:cluster-pekko"))
    api(project(":gm:gm-core"))
    api(project(":script:script-core"))
    api(project(":script:script-job"))
    testImplementation(libs.kotlinx.coroutines.core)
}
