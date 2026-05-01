dependencies {
    api(project(":asteria-core"))
    api(project(":asteria-message"))
    api(project(":asteria-rpc"))
    api(project(":asteria-cluster-config"))
    api(libs.pekko.actor)
    api(libs.pekko.cluster.sharding)
    api(libs.pekko.cluster.tools)
    api(libs.typesafe.config)
}
