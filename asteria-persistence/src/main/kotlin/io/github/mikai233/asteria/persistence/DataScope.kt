package io.github.mikai233.asteria.persistence

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.ServiceRegistry

data class DataScope<ID : Any>(
    val entityKind: EntityKind,
    val entityId: ID,
    val services: ServiceRegistry,
)

/**
 * Factory for one actor-local data unit.
 */
fun interface DataModule<ID : Any> {
    fun create(scope: DataScope<ID>): MemData
}
