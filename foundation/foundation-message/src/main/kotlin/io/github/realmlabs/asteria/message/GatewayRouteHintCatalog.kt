package io.github.realmlabs.asteria.message

import kotlin.reflect.KClass

data class GatewayRouteHintEntry(
    val messageClass: KClass<*>,
    val handlerClass: KClass<*>,
    val route: String,
    val entityId: String,
    val inject: List<String>,
    val clearFields: List<String>,
)

interface GatewayRouteHintCatalog {
    val routes: List<GatewayRouteHintEntry>
}
