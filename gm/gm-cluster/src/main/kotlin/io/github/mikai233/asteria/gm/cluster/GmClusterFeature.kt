package io.github.mikai233.asteria.gm.cluster

import io.github.mikai233.asteria.gm.core.*

/**
 * Permission keys contributed by the cluster GM feature.
 */
object GmClusterPermissions {
    val Read: GmPermissionKey = GmPermissionKey("gm.cluster.read")
    val ManagementRaw: GmPermissionKey = GmPermissionKey("gm.cluster.management.raw")
    val QueryActor: GmPermissionKey = GmPermissionKey("gm.cluster.actor.query")
    val Leave: GmPermissionKey = GmPermissionKey("gm.cluster.control.leave")
    val Join: GmPermissionKey = GmPermissionKey("gm.cluster.control.join")
    val Down: GmPermissionKey = GmPermissionKey("gm.cluster.control.down")
}

/**
 * Built-in GM feature for cluster status and actor inspection.
 *
 * This module only defines runtime-neutral contracts. Concrete adapters, such as Pekko, provide the actual
 * implementations of status collection and actor queries.
 */
class GmClusterFeature : GmFeature {
    override val descriptor: GmFeatureDescriptor = GmFeatureDescriptor(
        id = GmFeatureId("cluster"),
        name = "Cluster",
        description = "Cluster status, topology, and actor inspection.",
        permissions = listOf(
            GmPermission(
                key = GmClusterPermissions.Read,
                name = "Read cluster status",
                description = "Allows reading cluster topology and runtime member status.",
            ),
            GmPermission(
                key = GmClusterPermissions.QueryActor,
                name = "Query actors",
                description = "Allows sending diagnostic queries to runtime actors.",
                highRisk = true,
            ),
            GmPermission(
                key = GmClusterPermissions.ManagementRaw,
                name = "Read raw cluster management state",
                description = "Allows reading raw cluster management responses from the runtime.",
                highRisk = true,
            ),
            GmPermission(
                key = GmClusterPermissions.Leave,
                name = "Leave cluster nodes",
                description = "Allows gracefully removing nodes from the cluster.",
                highRisk = true,
            ),
            GmPermission(
                key = GmClusterPermissions.Join,
                name = "Join cluster nodes",
                description = "Allows asking a node to join an existing cluster.",
                highRisk = true,
            ),
            GmPermission(
                key = GmClusterPermissions.Down,
                name = "Down cluster nodes",
                description = "Allows forcibly marking cluster nodes as down.",
                highRisk = true,
            ),
        ),
        menus = listOf(
            GmMenuItem(
                id = "cluster",
                title = "Cluster",
                route = "/cluster",
                permission = GmClusterPermissions.Read,
                order = 200,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "cluster.overview",
                path = "/cluster",
                component = "asteria/cluster/ClusterOverview",
                permission = GmClusterPermissions.Read,
            ),
            GmRoute(
                id = "cluster.actor-query",
                path = "/cluster/actor-query",
                component = "asteria/cluster/ActorQuery",
                permission = GmClusterPermissions.QueryActor,
            ),
        ),
    )
}
