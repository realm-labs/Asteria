package io.github.mikai233.asteria.gm.config.spring

import io.github.mikai233.asteria.config.ConfigTableName
import io.github.mikai233.asteria.gm.config.GmConfigInspector
import io.github.mikai233.asteria.gm.config.GmConfigMetadata
import io.github.mikai233.asteria.gm.config.GmConfigPermissions
import io.github.mikai233.asteria.gm.config.GmConfigRow
import io.github.mikai233.asteria.gm.config.GmConfigRowPage
import io.github.mikai233.asteria.gm.config.GmConfigRowQuery
import io.github.mikai233.asteria.gm.config.GmConfigTableDescriptor
import io.github.mikai233.asteria.gm.config.GmConfigTableSummary
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP API for browsing the config snapshot currently used by the running server.
 */
@RestController
@RequestMapping($$"${asteria.gm.api-prefix:/gm/api}/config")
class GmConfigController(
    private val inspector: GmConfigInspector,
    private val endpoints: GmEndpointSupport,
) {
    @GetMapping("/metadata")
    suspend fun metadata(request: HttpServletRequest): GmConfigMetadata {
        return endpoints.execute(
            request = request,
            permission = GmConfigPermissions.Read,
            action = "gm.config.metadata",
        ) {
            inspector.metadata()
        }
    }

    @GetMapping("/tables")
    suspend fun tables(request: HttpServletRequest): List<GmConfigTableSummary> {
        return endpoints.execute(
            request = request,
            permission = GmConfigPermissions.Read,
            action = "gm.config.tables.list",
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
            permission = GmConfigPermissions.Read,
            action = "gm.config.tables.schema",
            attributes = mapOf("table" to table),
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
            permission = GmConfigPermissions.Read,
            action = "gm.config.rows.list",
            attributes = mapOf("table" to table),
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
            permission = GmConfigPermissions.Read,
            action = "gm.config.rows.query",
            attributes = mapOf("table" to table),
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
            permission = GmConfigPermissions.Read,
            action = "gm.config.rows.find",
            attributes = mapOf(
                "table" to table,
                "id" to id,
            ),
        ) {
            inspector.findRow(ConfigTableName(table), id)
                ?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.notFound().build()
        }
    }
}
