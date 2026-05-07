package io.github.realmlabs.asteria.message

import kotlin.reflect.KClass

/**
 * Tooling hint for gateway-side route intent resolution.
 *
 * These hints are metadata only; the framework does not execute them automatically in `foundation-message`.
 */
data class GatewayRouteHintEntry(
    val messageClass: KClass<*>,
    val handlerClass: KClass<*>,
    val route: String,
)

/**
 * Catalog of gateway routing hints, usually generated alongside handler metadata.
 */
interface GatewayRouteHintCatalog {
    val routes: List<GatewayRouteHintEntry>
}
