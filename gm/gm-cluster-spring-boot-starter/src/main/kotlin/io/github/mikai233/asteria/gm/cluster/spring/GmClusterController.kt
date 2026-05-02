package io.github.mikai233.asteria.gm.cluster.spring

import io.github.mikai233.asteria.gm.cluster.GmClusterPermissions
import io.github.mikai233.asteria.gm.cluster.GmClusterControlService
import io.github.mikai233.asteria.gm.cluster.GmClusterDownRequest
import io.github.mikai233.asteria.gm.cluster.GmClusterJoinRequest
import io.github.mikai233.asteria.gm.cluster.GmClusterLeaveRequest
import io.github.mikai233.asteria.gm.cluster.GmClusterOperationResult
import io.github.mikai233.asteria.gm.cluster.GmClusterRawStatusService
import io.github.mikai233.asteria.gm.cluster.GmClusterStatus
import io.github.mikai233.asteria.gm.cluster.GmClusterStatusService
import io.github.mikai233.asteria.gm.core.GmResourceScope
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
            permission = GmClusterPermissions.Read,
            action = "gm.cluster.status",
        ) {
            statusService.current()
        }
    }

    @GetMapping("/management/raw")
    suspend fun rawStatus(request: HttpServletRequest): String {
        val service = rawStatusService ?: error("GM cluster raw management status service is not configured")
        return endpoints.execute(
            request = request,
            permission = GmClusterPermissions.ManagementRaw,
            action = "gm.cluster.management.raw",
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
            permission = GmClusterPermissions.Leave,
            action = "gm.cluster.leave",
            scope = GmResourceScope(mapOf("clusterNode" to body.address)),
            attributes = mapOf(
                "targetAddress" to body.address,
                "reason" to body.reason,
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
            permission = GmClusterPermissions.Join,
            action = "gm.cluster.join",
            scope = GmResourceScope(mapOf("clusterNode" to body.nodeAddress)),
            attributes = mapOf(
                "targetAddress" to body.nodeAddress,
                "reason" to body.reason,
            ) + listOfNotNull(body.seedAddress?.let { "seedAddress" to it }),
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
            permission = GmClusterPermissions.Down,
            action = "gm.cluster.down",
            scope = GmResourceScope(mapOf("clusterNode" to body.address)),
            attributes = mapOf(
                "targetAddress" to body.address,
                "reason" to body.reason,
                "confirmed" to body.confirmed.toString(),
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
