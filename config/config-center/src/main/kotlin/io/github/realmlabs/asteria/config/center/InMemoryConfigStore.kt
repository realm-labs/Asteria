package io.github.realmlabs.asteria.config.center

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local [ConfigStore] implementation for tests and lightweight local wiring.
 *
 * State lives only in memory, revisions are simple incrementing numbers, and watches only observe mutations performed
 * through this instance. It is not intended for cross-process coordination or durable operational config storage.
 */
class InMemoryConfigStore(
    initialEntries: Iterable<ConfigEntry> = emptyList(),
) : ConfigStore {
    private val entries: MutableMap<ConfigPath, ConfigEntry> = linkedMapOf()
    private val watchers: CopyOnWriteArrayList<InMemoryConfigWatch> = CopyOnWriteArrayList()
    private val revisions: AtomicLong = AtomicLong(0)
    private val lock: Mutex = Mutex()

    init {
        initialEntries.forEach { entry ->
            entries[entry.path] = entry.copy(bytes = entry.bytes.copyOf())
        }
    }

    override suspend fun get(path: ConfigPath): ConfigEntry? {
        return lock.withLock {
            entries[path]?.copyEntry()
        }
    }

    override suspend fun children(path: ConfigPath): List<ConfigEntry> {
        return lock.withLock {
            entries.values
                .asSequence()
                .filter { it.path.isChildOf(path) }
                .sortedBy { it.path.value }
                .map { it.copyEntry() }
                .toList()
        }
    }

    override fun watch(
        path: ConfigPath,
        mode: ConfigWatchMode,
    ): ConfigWatch {
        return InMemoryConfigWatch(path, mode).also(watchers::add)
    }

    override suspend fun put(
        path: ConfigPath,
        bytes: ByteArray,
        expectedRevision: ConfigRevision?,
    ): ConfigRevision {
        val event = lock.withLock {
            val current = entries[path]
            checkRevision(path, expectedRevision, current?.revision)

            val revision = nextRevision()
            val entry = ConfigEntry(path, bytes.copyOf(), revision)
            entries[path] = entry
            ConfigEvent.Upserted(path, entry.copyEntry())
        }
        publish(event)
        return event.entry.revision
    }

    override suspend fun delete(
        path: ConfigPath,
        expectedRevision: ConfigRevision?,
    ) {
        val event = lock.withLock {
            val current = entries[path]
            checkRevision(path, expectedRevision, current?.revision)
            entries.remove(path)
            ConfigEvent.Deleted(path, current?.copyEntry())
        }
        publish(event)
    }

    private fun checkRevision(
        path: ConfigPath,
        expected: ConfigRevision?,
        actual: ConfigRevision?,
    ) {
        if (expected != null && expected != actual) {
            throw ConfigRevisionMismatchException(path, expected, actual)
        }
    }

    private fun nextRevision(): ConfigRevision {
        return ConfigRevision(revisions.incrementAndGet().toString())
    }

    private suspend fun publish(event: ConfigEvent) {
        val matchedWatchers = watchers.filter { it.matches(event.path) }
        for (watcher in matchedWatchers) {
            watcher.emit(event)
        }
    }

    private fun ConfigEntry.copyEntry(): ConfigEntry {
        return copy(bytes = bytes.copyOf())
    }

    private inner class InMemoryConfigWatch(
        private val path: ConfigPath,
        private val mode: ConfigWatchMode,
    ) : ConfigWatch {
        private val flow: MutableSharedFlow<ConfigEvent> = MutableSharedFlow(extraBufferCapacity = 64)

        override val events: Flow<ConfigEvent> = flow

        fun matches(changedPath: ConfigPath): Boolean {
            return when (mode) {
                ConfigWatchMode.Value -> changedPath == path
                ConfigWatchMode.Children -> changedPath.isChildOf(path)
                ConfigWatchMode.Tree -> changedPath == path || changedPath.isDescendantOf(path)
            }
        }

        suspend fun emit(event: ConfigEvent) {
            flow.emit(event)
        }

        override fun close() {
            watchers -= this
        }
    }
}
