dependencies {
    api(project(":asteria-core"))
    api(project(":asteria-observability-core"))
    api(libs.pekko.actor)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.jdk8)
}
