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
        while (true) {
            val current = store.get(path)
            if (current == null) {
                try {
                    store.put(path, "1".toByteArray(UTF_8))
                    return 1
                } catch (_: ConfigRevisionMismatchException) {
                    continue
                }
            }
            val next = current.bytes.toString(UTF_8).toLong() + 1
            try {
                store.put(path, next.toString().toByteArray(UTF_8), current.revision)
                return next
            } catch (_: ConfigRevisionMismatchException) {
                continue
            }
        }
    }

    suspend fun upsert(
        path: ConfigPath,
        bytes: ByteArray,
    ) {
        store.put(path, bytes)
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
