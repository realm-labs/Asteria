package io.github.realmlabs.asteria.cluster.pekko

import io.github.realmlabs.asteria.cluster.config.ClusterTopology
import io.github.realmlabs.asteria.cluster.config.ClusterViewNode
import io.github.realmlabs.asteria.cluster.config.ClusterViewNodeStatus
import io.github.realmlabs.asteria.cluster.config.ClusterViewService
import io.github.realmlabs.asteria.cluster.config.ClusterViewSnapshot
import io.github.realmlabs.asteria.cluster.config.RuntimeNodeConfig
import io.github.realmlabs.asteria.core.RoleKey
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.Member
import org.apache.pekko.cluster.MemberStatus

class PekkoClusterViewService(
    private val system: ActorSystem,
    private val topology: ClusterTopology,
    private val appName: String,
) : ClusterViewService {
    init {
        require(appName.isNotBlank()) { "cluster view app name must not be blank" }
    }

    override suspend fun snapshot(): ClusterViewSnapshot {
        val cluster = Cluster.get(system)
        val membersByAddress = cluster.state().members
            .filter { it.status() != MemberStatus.removed() }
            .associateBy { it.address().toString() }
        val configured = topology.nodes.map { node ->
            val address = node.pekkoAddress(system.name())
            node.toClusterViewNode(address, membersByAddress[address])
        }
        val configuredAddresses = configured.mapNotNullTo(linkedSetOf()) { it.address }
        val discovered = membersByAddress
            .filterKeys { it !in configuredAddresses }
            .values
            .map { member -> member.toClusterViewNode() }
        return ClusterViewSnapshot((configured + discovered).sortedBy { it.address ?: it.nodeId })
    }

    private fun RuntimeNodeConfig.toClusterViewNode(
        address: String,
        member: Member?,
    ): ClusterViewNode {
        return ClusterViewNode(
            nodeId = nodeId,
            address = address,
            appName = appName,
            version = attributes["version"] ?: attributes["appVersion"],
            roles = roles.mapTo(linkedSetOf(), ::RoleKey),
            status = if (member == null) ClusterViewNodeStatus.Expected else ClusterViewNodeStatus.Reachable,
            configured = true,
            attributes = attributes,
        )
    }

    private fun Member.toClusterViewNode(): ClusterViewNode {
        return ClusterViewNode(
            nodeId = uniqueAddress().longUid().toString(),
            address = address().toString(),
            appName = appName,
            version = appVersion().toString(),
            roles = getRoles().mapTo(linkedSetOf(), ::RoleKey),
            status = ClusterViewNodeStatus.Reachable,
            configured = false,
            attributes = mapOf(
                "dataCenter" to dataCenter(),
                "appVersion" to appVersion().toString(),
            ),
        )
    }
}

private fun RuntimeNodeConfig.pekkoAddress(systemName: String): String {
    return attributes["address"] ?: attributes["pekkoAddress"] ?: "pekko://$systemName@$host:$port"
}
