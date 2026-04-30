package io.github.mikai233.asteria.persistence

interface MemData {
    suspend fun load()
}

interface AutoFlushMemData : MemData {
    suspend fun tick()

    suspend fun flush(): Boolean
}
