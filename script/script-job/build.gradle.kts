plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":foundation:foundation-core"))
    api(project(":observability:observability-core"))
    api(project(":script:script-core"))
}
