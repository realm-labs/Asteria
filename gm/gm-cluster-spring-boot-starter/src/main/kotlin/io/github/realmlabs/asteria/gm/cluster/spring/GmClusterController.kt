package io.github.realmlabs.asteria.gm.cluster.spring

import io.github.realmlabs.asteria.gm.cluster.*
import io.github.realmlabs.asteria.gm.core.GmOperation
import io.github.realmlabs.asteria.gm.core.GmResource
import io.github.realmlabs.asteria.gm.core.GmRiskLevel
import io.github.realmlabs.asteria.gm.spring.GmEndpointSupport
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.*

/**
 * HTTP API for cluster GM tools.
 */
@RestController
@RequestMapping($$"${asteria.gm.api-prefix:/gm/api}/cluster")
class GmClusterController(
    private val statusService: GmClusterStatusService,
    private val endpoints: GmEndpointSupport,
    private val rawStatusService: GmClusterRawStatusService? = null,
    private val controlService: GmClusterControlService? = null,
) {
    @GetMapping("/status")
    suspend fun status(request: HttpServletRequest): GmClusterStatus {
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmClusterActions.Read, GmResource("cluster.status")),
        ) {
            statusService.current()
        }
    }

    @GetMapping("/management/raw")
    suspend fun rawStatus(request: HttpServletRequest): String {
        val service = rawStatusService ?: error("GM cluster raw management status service is not configured")
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmClusterActions.ManagementRaw,
                resource = GmResource("cluster.management.raw"),
                risk = GmRiskLevel.High,
            ),
        ) {
            service.rawStatus()
        }
    }

    @PostMapping("/actions/leave")
    suspend fun leave(
        request: HttpServletRequest,
        @RequestBody body: GmClusterLeaveHttpRequest,
    ): GmClusterOperationResult {
        val service = controlService ?: error("GM cluster control service is not configured")
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmClusterActions.Leave,
                resource = GmResource("cluster.node", body.address),
                risk = GmRiskLevel.High,
                attributes = mapOf(
                    "targetAddress" to body.address,
                    "reason" to body.reason,
                ),
            ),
        ) { context ->
            service.leave(
                GmClusterLeaveRequest(
                    address = body.address,
                    requestedBy = context.principal.id,
                    reason = body.reason,
                ),
            )
        }
    }

    @PostMapping("/actions/join")
    suspend fun join(
        request: HttpServletRequest,
        @RequestBody body: GmClusterJoinHttpRequest,
    ): GmClusterOperationResult {
        val service = controlService ?: error("GM cluster control service is not configured")
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmClusterActions.Join,
                resource = GmResource("cluster.node", body.nodeAddress),
                risk = GmRiskLevel.High,
                attributes = mapOf(
                    "targetAddress" to body.nodeAddress,
                    "reason" to body.reason,
                ) + listOfNotNull(body.seedAddress?.let { "seedAddress" to it }),
            ),
        ) { context ->
            service.join(
                GmClusterJoinRequest(
                    nodeAddress = body.nodeAddress,
                    seedAddress = body.seedAddress,
                    requestedBy = context.principal.id,
                    reason = body.reason,
                ),
            )
        }
    }

    @PostMapping("/actions/down")
    suspend fun down(
        request: HttpServletRequest,
        @RequestBody body: GmClusterDownHttpRequest,
    ): GmClusterOperationResult {
        val service = controlService ?: error("GM cluster control service is not configured")
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmClusterActions.Down,
                resource = GmResource("cluster.node", body.address),
                risk = GmRiskLevel.High,
                attributes = mapOf(
                    "targetAddress" to body.address,
                    "reason" to body.reason,
                    "confirmed" to body.confirmed.toString(),
                ),
            ),
        ) { context ->
            service.down(
                GmClusterDownRequest(
                    address = body.address,
                    requestedBy = context.principal.id,
                    reason = body.reason,
                    confirmed = body.confirmed,
                ),
            )
        }
    }
}
