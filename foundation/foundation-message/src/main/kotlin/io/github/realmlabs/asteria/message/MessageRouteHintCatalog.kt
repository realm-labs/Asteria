package io.github.realmlabs.asteria.message

import kotlin.reflect.KClass

/**
 * Tooling hint for message route intent resolution.
 *
 * These hints are metadata only; the framework does not execute them automatically in `foundation-message`.
 */
data class MessageRouteHintEntry(
    val messageClass: KClass<*>,
    val handlerClass: KClass<*>,
    val route: String,
)

/**
 * Catalog of message routing hints, usually generated alongside handler metadata.
 */
interface MessageRouteHintCatalog {
    val routes: List<MessageRouteHintEntry>
}
