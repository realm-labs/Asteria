package io.github.mikai233.asteria.persistence

import kotlin.reflect.KClass

interface PersistenceProvider {
    suspend fun <ID : Any, E : Entity<ID>> find(type: KClass<E>, id: ID): E?

    suspend fun <ID : Any, E : Entity<ID>> save(type: KClass<E>, entity: E)

    suspend fun <ID : Any, E : Entity<ID>> delete(type: KClass<E>, id: ID)
}
