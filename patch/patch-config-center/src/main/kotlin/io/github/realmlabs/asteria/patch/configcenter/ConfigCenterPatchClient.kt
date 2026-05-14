package io.github.realmlabs.asteria.patch.configcenter

import io.github.realmlabs.asteria.config.center.ConfigEntry
import io.github.realmlabs.asteria.config.center.ConfigPath
import io.github.realmlabs.asteria.config.center.ConfigRevisionMismatchException
import io.github.realmlabs.asteria.config.center.ConfigStore
import java.nio.charset.StandardCharsets.UTF_8

internal class ConfigCenterPatchClient(
    private val store: ConfigStore,
) {
    suspend fun incrementCounter(path: ConfigPath): Long {
        var next = 0L
        store.update(path) { current ->
            next = current?.bytes?.toString(UTF_8)?.toLong()?.plus(1) ?: 1
            next.toString().toByteArray(UTF_8)
        }
        return next
    }

    suspend fun upsert(
        path: ConfigPath,
        bytes: ByteArray,
    ) {
        store.upsert(path, bytes)
    }

    suspend fun read(path: ConfigPath): ByteArray? {
        return store.get(path)?.bytes
    }

    suspend fun children(path: ConfigPath): List<ConfigEntry> {
        return store.children(path)
    }

    suspend fun deleteIfExists(path: ConfigPath) {
        store.get(path)?.let { entry ->
            try {
                store.delete(path, entry.revision)
            } catch (_: ConfigRevisionMismatchException) {
                store.delete(path)
            }
        }
    }
}
