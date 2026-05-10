package io.github.realmlabs.asteria.gm.cluster

import io.github.realmlabs.asteria.gm.core.*

/**
 * Action keys contributed by the cluster GM feature.
 */
object GmClusterActions {
    val Read: GmAction = GmAction("gm.cluster.read")
    val ManagementRaw: GmAction = GmAction("gm.cluster.management.raw")
    val QueryActor: GmAction = GmAction("gm.cluster.actor.query")
    val Leave: GmAction = GmAction("gm.cluster.control.leave")
    val Join: GmAction = GmAction("gm.cluster.control.join")
    val Down: GmAction = GmAction("gm.cluster.control.down")
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
        actions = listOf(
            GmActionDescriptor(
                key = GmClusterActions.Read,
                name = "Read cluster status",
                description = "Allows reading cluster topology and runtime member status.",
            ),
            GmActionDescriptor(
                key = GmClusterActions.QueryActor,
                name = "Query actors",
                description = "Allows sending diagnostic queries to runtime actors.",
                risk = GmRiskLevel.High,
            ),
            GmActionDescriptor(
                key = GmClusterActions.ManagementRaw,
                name = "Read raw cluster management state",
                description = "Allows reading raw cluster management responses from the runtime.",
                risk = GmRiskLevel.High,
            ),
            GmActionDescriptor(
                key = GmClusterActions.Leave,
                name = "Leave cluster nodes",
                description = "Allows gracefully removing nodes from the cluster.",
                risk = GmRiskLevel.High,
            ),
            GmActionDescriptor(
                key = GmClusterActions.Join,
                name = "Join cluster nodes",
                description = "Allows asking a node to join an existing cluster.",
                risk = GmRiskLevel.High,
            ),
            GmActionDescriptor(
                key = GmClusterActions.Down,
                name = "Down cluster nodes",
                description = "Allows forcibly marking cluster nodes as down.",
                risk = GmRiskLevel.High,
            ),
        ),
        menus = listOf(
            GmMenuItem(
                id = "cluster",
                title = "Cluster",
                route = "/cluster",
                action = GmClusterActions.Read,
                order = 200,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "cluster.overview",
                path = "/cluster",
                component = "asteria/cluster/ClusterOverview",
                action = GmClusterActions.Read,
            ),
            GmRoute(
                id = "cluster.actor-query",
                path = "/cluster/actor-query",
                component = "asteria/cluster/ActorQuery",
                action = GmClusterActions.QueryActor,
            ),
        ),
    )
}
