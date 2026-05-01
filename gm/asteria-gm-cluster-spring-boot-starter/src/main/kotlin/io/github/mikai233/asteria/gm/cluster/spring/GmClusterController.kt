package io.github.mikai233.asteria.gm.cluster.spring

import io.github.mikai233.asteria.gm.cluster.GmClusterPermissions
import io.github.mikai233.asteria.gm.cluster.GmClusterStatus
import io.github.mikai233.asteria.gm.cluster.GmClusterStatusService
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.GetMapping
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
) {
    @GetMapping("/status")
    fun status(request: HttpServletRequest): GmClusterStatus {
        return runBlocking {
            endpoints.execute(
                request = request,
                permission = GmClusterPermissions.Read,
                action = "gm.cluster.status",
            ) {
                statusService.current()
            }
        }
    }
}
