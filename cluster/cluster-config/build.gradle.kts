plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    api(project(":config:config-core"))
    api(project(":config:config-center"))
    api(libs.typesafe.config)
}
