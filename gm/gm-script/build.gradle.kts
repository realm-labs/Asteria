plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":gm:gm-core"))
    api(project(":script:script-core"))
    api(project(":script:script-job"))
}
