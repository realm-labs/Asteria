package io.github.realmlabs.asteria.gm.configcenter.spring

import io.github.realmlabs.asteria.gm.configcenter.ConfigCenterBrowser
import io.github.realmlabs.asteria.gm.configcenter.ConfigCenterBrowserAccessException
import io.github.realmlabs.asteria.gm.configcenter.ConfigCenterBrowserUnavailableException
import io.github.realmlabs.asteria.gm.configcenter.GmConfigCenterActions
import io.github.realmlabs.asteria.gm.configcenter.GmConfigCenterEntryResponse
import io.github.realmlabs.asteria.gm.configcenter.GmConfigCenterTreeResponse
import io.github.realmlabs.asteria.gm.core.GmOperation
import io.github.realmlabs.asteria.gm.core.GmResource
import io.github.realmlabs.asteria.gm.spring.GmEndpointSupport
import io.github.realmlabs.asteria.gm.spring.GmErrorResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Read-only HTTP API for browsing raw ConfigStore entries.
 */
@RestController
@RequestMapping($$"${asteria.gm.api-prefix:/gm/api}/config-center")
class GmConfigCenterController(
    private val browser: ConfigCenterBrowser,
    private val endpoints: GmEndpointSupport,
) {
    @GetMapping("/tree")
    suspend fun tree(
        request: HttpServletRequest,
        @RequestParam path: String = "/",
    ): GmConfigCenterTreeResponse {
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmConfigCenterActions.Read, GmResource("config-center.tree", path)),
        ) {
            browser.tree(path)
        }
    }

    @GetMapping("/entry")
    suspend fun entry(
        request: HttpServletRequest,
        @RequestParam path: String,
    ): GmConfigCenterEntryResponse {
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmConfigCenterActions.Read, GmResource("config-center.entry", path)),
        ) {
            browser.entry(path)
        }
    }
}

/**
 * Converts ConfigCenter browser failures into sanitized HTTP errors.
 */
@RestControllerAdvice
class GmConfigCenterExceptionHandler {
    @org.springframework.web.bind.annotation.ExceptionHandler(ConfigCenterBrowserAccessException::class)
    fun accessDenied(error: ConfigCenterBrowserAccessException): ResponseEntity<GmErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(GmErrorResponse("gm.config_center.access_denied", error.message ?: "config center path is not allowed"))
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ConfigCenterBrowserUnavailableException::class)
    fun unavailable(error: ConfigCenterBrowserUnavailableException): Nothing {
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            error.message ?: "config center is unavailable",
        )
    }
}
