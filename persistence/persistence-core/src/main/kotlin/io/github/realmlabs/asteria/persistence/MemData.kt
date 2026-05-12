package io.github.realmlabs.asteria.persistence

/**
 * Actor-local data unit.
 *
 * A data unit should load all state it needs before the actor starts handling business messages. Mutations after load
 * are expected to happen on the owning actor thread.
 */
interface MemData {
    /**
     * Loads the durable state needed by this data unit before normal use.
     *
     * Throwing from this method fails the enclosing [DataManager] operation; the manager records metrics and does not
     * install a partially loaded instance.
     */
    suspend fun load()
}

/**
 * Data unit that owns buffered writes.
 */
interface AutoFlushMemData : MemData {
    /**
     * Periodic maintenance hook, usually called by the owning actor's timer.
     */
    suspend fun tick()

    /**
     * Performs ordinary write flushing and returns whether it completed successfully.
     *
     * Returning false leaves the data loaded and eligible for a later retry.
     */
    suspend fun flush(): Boolean

    /**
     * Drains all writes required before unload or shutdown and returns whether the data is clean.
     *
     * Implementations should attempt all currently known writes. Returning false tells the caller that unloading or
     * shutdown should be delayed because durable state is not clean.
     */
    suspend fun drain(): Boolean
}
