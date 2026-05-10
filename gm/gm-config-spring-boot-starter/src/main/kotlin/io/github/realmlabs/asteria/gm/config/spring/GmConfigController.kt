package io.github.realmlabs.asteria.gm.config.spring

import io.github.realmlabs.asteria.cluster.config.ClusterConfigControlService
import io.github.realmlabs.asteria.cluster.config.ClusterConfigNodeStatus
import io.github.realmlabs.asteria.cluster.config.ClusterConfigReloadResult
import io.github.realmlabs.asteria.cluster.config.ClusterConfigRevisionConsistency
import io.github.realmlabs.asteria.config.ConfigTableName
import io.github.realmlabs.asteria.gm.config.*
import io.github.realmlabs.asteria.gm.core.GmOperation
import io.github.realmlabs.asteria.gm.core.GmResource
import io.github.realmlabs.asteria.gm.core.GmRiskLevel
import io.github.realmlabs.asteria.gm.spring.GmEndpointSupport
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * HTTP API for browsing the config snapshot currently used by the running server.
 */
@RestController
@RequestMapping($$"${asteria.gm.api-prefix:/gm/api}/config")
class GmConfigController(
    private val inspector: GmConfigInspector,
    private val endpoints: GmEndpointSupport,
    private val clusterControl: ClusterConfigControlService? = null,
) {
    @GetMapping("/metadata")
    suspend fun metadata(request: HttpServletRequest): GmConfigMetadata {
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmConfigActions.Read, GmResource("config.metadata")),
        ) {
            inspector.metadata()
        }
    }

    @GetMapping("/reload/status")
    suspend fun reloadStatus(request: HttpServletRequest): GmConfigReloadStatus {
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmConfigActions.Read, GmResource("config.reload.status")),
        ) {
            inspector.reloadStatus()
        }
    }

    @GetMapping("/reload/history")
    suspend fun reloadHistory(
        request: HttpServletRequest,
        @RequestParam limit: Int = 20,
    ): List<GmConfigReloadRecord> {
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmConfigActions.Read,
                resource = GmResource("config.reload.history"),
                attributes = mapOf("limit" to limit.toString()),
            ),
        ) {
            inspector.reloadHistory(limit)
        }
    }

    @PostMapping("/reload")
    suspend fun reload(request: HttpServletRequest): GmConfigReloadRecord {
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmConfigActions.Reload,
                resource = GmResource("config.snapshot"),
                risk = GmRiskLevel.High,
            ),
        ) {
            inspector.reloadNow()
        }
    }

    @GetMapping("/cluster/status")
    suspend fun clusterStatus(request: HttpServletRequest): List<ClusterConfigNodeStatus> {
        val control = clusterControl ?: error("cluster config control service is not configured")
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmConfigActions.Read, GmResource("config.cluster.status")),
        ) {
            control.statuses()
        }
    }

    @GetMapping("/cluster/consistency")
    suspend fun clusterConsistency(request: HttpServletRequest): ClusterConfigRevisionConsistency {
        val control = clusterControl ?: error("cluster config control service is not configured")
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmConfigActions.Read, GmResource("config.cluster.consistency")),
        ) {
            control.checkConsistency()
        }
    }

    @PostMapping("/cluster/reload")
    suspend fun clusterReload(
        request: HttpServletRequest,
        @RequestBody body: GmClusterConfigReloadHttpRequest,
    ): ClusterConfigReloadResult {
        val control = clusterControl ?: error("cluster config control service is not configured")
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmConfigActions.Reload,
                resource = GmResource("config.cluster.reload", body.target),
                risk = GmRiskLevel.High,
                attributes = mapOf("target" to body.target),
            ),
        ) {
            control.reload(body.reloadTarget(), body.timeout())
        }
    }

    @GetMapping("/tables")
    suspend fun tables(request: HttpServletRequest): List<GmConfigTableSummary> {
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmConfigActions.Read, GmResource("config.tables")),
        ) {
            inspector.listTables()
        }
    }

    @GetMapping("/tables/{table}/schema")
    suspend fun schema(
        request: HttpServletRequest,
        @PathVariable table: String,
    ): GmConfigTableDescriptor {
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmConfigActions.Read, GmResource("config.table.schema", table)),
        ) {
            inspector.describeTable(ConfigTableName(table))
        }
    }

    @GetMapping("/tables/{table}/rows")
    suspend fun rows(
        request: HttpServletRequest,
        @PathVariable table: String,
        @RequestParam keyword: String? = null,
        @RequestParam offset: Int = 0,
        @RequestParam limit: Int = 100,
    ): GmConfigRowPage {
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmConfigActions.Read, GmResource("config.table.rows", table)),
        ) {
            inspector.queryRows(
                name = ConfigTableName(table),
                query = GmConfigRowQuery(
                    keyword = keyword,
                    offset = offset,
                    limit = limit,
                ),
            )
        }
    }

    @PostMapping("/tables/{table}/query")
    suspend fun query(
        request: HttpServletRequest,
        @PathVariable table: String,
        @RequestBody query: GmConfigRowQuery,
    ): GmConfigRowPage {
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmConfigActions.Read, GmResource("config.table.rows", table)),
        ) {
            inspector.queryRows(ConfigTableName(table), query)
        }
    }

    @GetMapping("/tables/{table}/rows/{id}")
    suspend fun row(
        request: HttpServletRequest,
        @PathVariable table: String,
        @PathVariable id: String,
    ): ResponseEntity<GmConfigRow> {
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmConfigActions.Read,
                resource = GmResource(
                    type = "config.table.row",
                    id = id,
                    attributes = mapOf("table" to table),
                ),
            ),
        ) {
            inspector.findRow(ConfigTableName(table), id)
                ?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.notFound().build()
        }
    }
}
