plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":broadcast:ephemeral-broadcast-core"))
    api(project(":foundation:foundation-protobuf"))
}
