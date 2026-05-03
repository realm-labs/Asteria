plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":rpc:rpc-protobuf"))
    api(libs.pekko.actor)
}
