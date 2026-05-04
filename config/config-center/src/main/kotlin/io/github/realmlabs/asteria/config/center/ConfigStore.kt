package io.github.realmlabs.asteria.config.center

import kotlinx.coroutines.flow.Flow

data class ConfigRevision(
    val version: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(version.isNotBlank()) { "config revision version must not be blank" }
    }
}

data class ConfigEntry(
    val path: ConfigPath,
    val bytes: ByteArray,
    val revision: ConfigRevision,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ConfigEntry) {
            return false
        }
        return path == other.path &&
                bytes.contentEquals(other.bytes) &&
                revision == other.revision
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + revision.hashCode()
        return result
    }
}

enum class ConfigWatchMode {
    Value,
    Children,
    Tree,
}

sealed interface ConfigEvent {
    val path: ConfigPath

    data class Resynced(
        override val path: ConfigPath,
        val mode: ConfigWatchMode,
    ) : ConfigEvent

    data class Upserted(
        override val path: ConfigPath,
        val entry: ConfigEntry,
    ) : ConfigEvent

    data class Deleted(
        override val path: ConfigPath,
        val previous: ConfigEntry?,
    ) : ConfigEvent
}

interface ConfigWatch : AutoCloseable {
    val events: Flow<ConfigEvent>
}

interface ConfigStore {
    suspend fun get(path: ConfigPath): ConfigEntry?

    suspend fun children(path: ConfigPath): List<ConfigEntry>

    fun watch(
        path: ConfigPath,
        mode: ConfigWatchMode = ConfigWatchMode.Value,
    ): ConfigWatch

    suspend fun put(
        path: ConfigPath,
        bytes: ByteArray,
        expectedRevision: ConfigRevision? = null,
    ): ConfigRevision

    suspend fun delete(
        path: ConfigPath,
        expectedRevision: ConfigRevision? = null,
    )
}

class ConfigRevisionMismatchException(
    val path: ConfigPath,
    val expected: ConfigRevision?,
    val actual: ConfigRevision?,
) : IllegalStateException("config revision mismatch at $path, expected=$expected, actual=$actual")
