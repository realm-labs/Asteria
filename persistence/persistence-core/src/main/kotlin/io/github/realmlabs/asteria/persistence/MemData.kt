package io.github.realmlabs.asteria.persistence

/**
 * Actor-local data unit.
 *
 * A data unit should load all state it needs before the actor starts handling business messages. Mutations after load
 * are expected to happen on the owning actor thread.
 */
interface MemData {
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
     * Flushes pending writes and returns whether the flush completed successfully.
     */
    suspend fun flush(): Boolean
}
