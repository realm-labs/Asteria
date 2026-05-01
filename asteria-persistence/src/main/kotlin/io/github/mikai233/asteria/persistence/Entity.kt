package io.github.mikai233.asteria.persistence

/**
 * Persistent game document identity.
 *
 * The core persistence module intentionally does not define `find/save/delete` repositories. Storage adapters such as
 * MongoDB own those semantics because partial updates, write batching, optimistic checks, and indexing strategies are
 * database-specific.
 */
interface Entity<ID : Any> {
    val id: ID
}
