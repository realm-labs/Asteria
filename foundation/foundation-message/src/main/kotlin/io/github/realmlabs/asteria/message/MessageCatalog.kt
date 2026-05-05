package io.github.realmlabs.asteria.message

import kotlin.reflect.KClass

/**
 * Static metadata describing one generated or declared message-handler binding.
 */
data class MessageCatalogEntry(
    val messageClass: KClass<*>,
    val handlerClass: KClass<*>,
    val dispatcher: String,
)

/**
 * Read-only catalog of message bindings, typically generated for tooling or diagnostics.
 */
interface MessageCatalog {
    val bindings: List<MessageCatalogEntry>

    /**
     * Returns bindings associated with one logical dispatcher name.
     */
    fun bindingsFor(dispatcher: String): List<MessageCatalogEntry> {
        return bindings.filter { it.dispatcher == dispatcher }
    }
}
