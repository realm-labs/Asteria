package io.github.realmlabs.asteria.message

import kotlin.reflect.KClass

data class MessageCatalogEntry(
    val messageClass: KClass<*>,
    val handlerClass: KClass<*>,
    val dispatcher: String,
)

interface MessageCatalog {
    val bindings: List<MessageCatalogEntry>

    fun bindingsFor(dispatcher: String): List<MessageCatalogEntry> {
        return bindings.filter { it.dispatcher == dispatcher }
    }
}
