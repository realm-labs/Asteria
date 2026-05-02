package io.github.mikai233.asteria.gm.cluster.pekko.management

import io.github.mikai233.asteria.gm.cluster.*

/**
 * GM status adapter backed by Pekko Management Cluster HTTP.
 */
class PekkoManagementGmClusterStatusService(
    private val client: PekkoManagementHttpClient,
    private val endpoints: PekkoManagementEndpointResolver,
) : GmClusterStatusService, GmClusterRawStatusService {
    override suspend fun current(): GmClusterStatus {
        val endpoint = endpoints.statusEndpoint()
        val members = client.members(endpoint)
        val unreachable = members.unreachable.toSet()
        return GmClusterStatus(
            nodes = members.members.map { member ->
                GmClusterNode(
                    nodeId = member.nodeUid,
                    address = member.node,
                    status = member.status,
                    roles = member.roles,
                    seed = member.node == members.oldest,
                    attributes = mapOf(
                        "managementEndpoint" to endpoint.normalizedBaseUrl,
                        "self" to (member.node == members.selfNode).toString(),
                        "leader" to (member.node == members.leader).toString(),
                        "oldest" to (member.node == members.oldest).toString(),
                        "unreachable" to (member.node in unreachable).toString(),
                    ),
                )
            },
        )
    }

    override suspend fun rawStatus(): String {
        return client.rawMembers(endpoints.statusEndpoint())
    }
}

/**
 * GM control adapter backed by Pekko Management Cluster HTTP.
 */
class PekkoManagementGmClusterControlService(
    private val client: PekkoManagementHttpClient,
    private val endpoints: PekkoManagementEndpointResolver,
) : GmClusterControlService {
    override suspend fun leave(request: GmClusterLeaveRequest): GmClusterOperationResult {
        val endpoint = endpoints.controlEndpoint(request.address)
        return client.leave(endpoint, request.address).toResult("leave", request.address)
    }

    override suspend fun join(request: GmClusterJoinRequest): GmClusterOperationResult {
        val endpoint = endpoints.joinEndpoint(request.nodeAddress)
        val seedAddress = request.seedAddress ?: request.nodeAddress
        return client.join(endpoint, seedAddress).toResult(
            action = "join",
            targetAddress = request.nodeAddress,
            attributes = mapOf("seedAddress" to seedAddress),
        )
    }

    override suspend fun down(request: GmClusterDownRequest): GmClusterOperationResult {
        require(request.confirmed) { "GM cluster down request must be explicitly confirmed" }
        val endpoint = endpoints.controlEndpoint(request.address)
        return client.down(endpoint, request.address).toResult("down", request.address)
    }
}

private fun PekkoManagementOperationResponse.toResult(
    action: String,
    targetAddress: String,
    attributes: Map<String, String> = emptyMap(),
): GmClusterOperationResult {
    return GmClusterOperationResult(
        action = action,
        targetAddress = targetAddress,
        accepted = true,
        message = body.ifBlank { "Pekko Management accepted $action for $targetAddress" },
        managementEndpoint = endpoint,
        attributes = attributes + mapOf("httpStatus" to statusCode.toString()),
    )
}
