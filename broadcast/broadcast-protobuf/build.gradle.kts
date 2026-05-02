plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":broadcast:broadcast-core"))
    api(project(":foundation:foundation-protobuf"))
}
