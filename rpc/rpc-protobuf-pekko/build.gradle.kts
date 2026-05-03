plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(project(":rpc:rpc-protobuf"))
    api(project(":cluster:cluster-pekko"))
    api(libs.pekko.actor)
}
