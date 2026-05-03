package io.github.realmlabs.asteria.gm.cluster.pekko

import io.github.realmlabs.asteria.gm.cluster.GmClusterNode
import io.github.realmlabs.asteria.gm.cluster.GmClusterStatus
import io.github.realmlabs.asteria.gm.cluster.GmClusterStatusService
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.Member

/**
 * GM cluster status adapter backed by a live Pekko Cluster.
 */
class PekkoGmClusterStatusService(
    private val system: ActorSystem,
) : GmClusterStatusService {
    override suspend fun current(): GmClusterStatus {
        val cluster = Cluster.get(system)
        val selfAddress = cluster.selfAddress()
        return GmClusterStatus(
            nodes = cluster.state().getMembers().map { member ->
                member.toGmNode(selfAddress == member.address())
            },
        )
    }
}

private fun Member.toGmNode(self: Boolean): GmClusterNode {
    return GmClusterNode(
        nodeId = uniqueAddress().longUid().toString(),
        address = address().toString(),
        status = status().toString(),
        roles = getRoles().toSet(),
        attributes = mapOf(
            "dataCenter" to dataCenter(),
            "appVersion" to appVersion().toString(),
            "self" to self.toString(),
        ),
    )
}
