plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":asteria-broadcast"))
    api(project(":asteria-protobuf"))
}
