package io.github.realmlabs.asteria.config.center.etcd

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.Watch
import io.etcd.jetcd.Watch.Watcher
import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.DeleteOption
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.PutOption
import io.etcd.jetcd.options.WatchOption
import io.github.realmlabs.asteria.config.center.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * etcd-backed [ConfigStore].
 *
 * Revisions are the entry `modRevision`, so they can be used as compare-and-set tokens and also reflect etcd's
 * monotonic ordering within the cluster. Watches are thin wrappers around jetcd watchers; if a watch closes because of
 * transport issues, higher-level helpers such as [io.github.realmlabs.asteria.config.center.RuntimeConfigRepository]
 * are responsible for recreating it.
 */
class EtcdConfigStore(
    private val client: Client,
    keyPrefix: String = "",
) : ConfigStore {
    private val normalizedPrefix = normalizePrefix(keyPrefix)

    override suspend fun get(path: ConfigPath): ConfigEntry? {
        val response = client.kvClient.get(byteKey(path)).await()
        return response.kvs.firstOrNull()?.toEntry()
    }

    override suspend fun children(path: ConfigPath): List<ConfigEntry> {
        val option = GetOption.builder().isPrefix(true).build()
        return client.kvClient.get(bytePrefix(path), option)
            .await()
            .kvs
            .mapNotNull { it.toEntryOrNull() }
            .filter { it.path.isChildOf(path) }
            .sortedBy { it.path.value }
    }

    override fun watch(
        path: ConfigPath,
        mode: ConfigWatchMode,
    ): ConfigWatch {
        val closed = AtomicBoolean(false)
        val watcherRef = AtomicReference<Watcher?>()
        val events = callbackFlow {
            if (closed.get()) {
                close()
                return@callbackFlow
            }
            val optionBuilder = WatchOption.builder().withPrevKV(true)
            if (mode != ConfigWatchMode.Value) {
                optionBuilder.isPrefix(true)
            }
            val watcher = client.watchClient.watch(
                watchKey(path, mode),
                optionBuilder.build(),
                Watch.listener(
                    { response ->
                        response.events.forEach { event ->
                            val configEvent = when (event.eventType) {
                                io.etcd.jetcd.watch.WatchEvent.EventType.PUT ->
                                    event.keyValue.toEntryOrNull()?.let { ConfigEvent.Upserted(it.path, it) }

                                io.etcd.jetcd.watch.WatchEvent.EventType.DELETE -> {
                                    val previous = event.prevKV.toEntryOrNull()
                                    val deletedPath = previous?.path ?: event.keyValue.toPathOrNull()
                                    deletedPath?.let { ConfigEvent.Deleted(it, previous) }
                                }

                                else -> null
                            }
                            if (configEvent != null && matches(path, mode, configEvent.path)) {
                                trySend(configEvent)
                            }
                        }
                    },
                    { error -> close(error) },
                    { close() },
                ),
            )
            watcherRef.set(watcher)
            awaitClose {
                watcherRef.compareAndSet(watcher, null)
                watcher.close()
            }
        }
        return object : ConfigWatch {
            override val events: Flow<ConfigEvent> = events

            override fun close() {
                closed.set(true)
                watcherRef.getAndSet(null)?.close()
            }
        }
    }

    override suspend fun put(
        path: ConfigPath,
        bytes: ByteArray,
        expectedRevision: ConfigRevision?,
    ): ConfigRevision {
        if (expectedRevision == null) {
            return upsert(path, bytes)
        } else {
            val response = client.kvClient.txn()
                .If(Cmp(byteKey(path), Cmp.Op.EQUAL, CmpTarget.modRevision(expectedRevision.version.toLong())))
                .Then(Op.put(byteKey(path), ByteSequence.from(bytes.copyOf()), PutOption.DEFAULT))
                .commit()
                .await()
            if (!response.isSucceeded) {
                throw ConfigRevisionMismatchException(path, expectedRevision, get(path)?.revision)
            }
        }
        return get(path)?.revision ?: ConfigRevision("0")
    }

    override suspend fun upsert(
        path: ConfigPath,
        bytes: ByteArray,
    ): ConfigRevision {
        client.kvClient.put(byteKey(path), ByteSequence.from(bytes.copyOf())).await()
        return get(path)?.revision ?: ConfigRevision("0")
    }

    override suspend fun update(
        path: ConfigPath,
        transform: suspend (current: ConfigEntry?) -> ByteArray?,
    ): ConfigEntry? {
        while (true) {
            val current = get(path)
            val bytes = transform(current?.copy(bytes = current.bytes.copyOf())) ?: return null
            val compare = if (current == null) {
                Cmp(byteKey(path), Cmp.Op.EQUAL, CmpTarget.version(0))
            } else {
                Cmp(byteKey(path), Cmp.Op.EQUAL, CmpTarget.modRevision(current.revision.version.toLong()))
            }
            val response = client.kvClient.txn()
                .If(compare)
                .Then(Op.put(byteKey(path), ByteSequence.from(bytes.copyOf()), PutOption.DEFAULT))
                .commit()
                .await()
            if (response.isSucceeded) {
                val revision = get(path)?.revision ?: ConfigRevision("0")
                return ConfigEntry(path, bytes.copyOf(), revision)
            }
        }
    }

    override suspend fun delete(
        path: ConfigPath,
        expectedRevision: ConfigRevision?,
    ) {
        if (expectedRevision == null) {
            client.kvClient.delete(byteKey(path), DeleteOption.DEFAULT).await()
        } else {
            val response = client.kvClient.txn()
                .If(Cmp(byteKey(path), Cmp.Op.EQUAL, CmpTarget.modRevision(expectedRevision.version.toLong())))
                .Then(Op.delete(byteKey(path), DeleteOption.DEFAULT))
                .commit()
                .await()
            if (!response.isSucceeded) {
                throw ConfigRevisionMismatchException(path, expectedRevision, get(path)?.revision)
            }
        }
    }

    private fun watchKey(
        path: ConfigPath,
        mode: ConfigWatchMode,
    ): ByteSequence {
        return when (mode) {
            ConfigWatchMode.Value -> byteKey(path)
            ConfigWatchMode.Children,
            ConfigWatchMode.Tree,
                -> bytePrefix(path)
        }
    }

    private fun byteKey(path: ConfigPath): ByteSequence {
        return ByteSequence.from(keyOf(path), UTF_8)
    }

    private fun bytePrefix(path: ConfigPath): ByteSequence {
        val key = keyOf(path)
        return ByteSequence.from(if (key.endsWith("/")) key else "$key/", UTF_8)
    }

    private fun keyOf(path: ConfigPath): String {
        return normalizedPrefix + path.value
    }

    private fun KeyValue.toEntry(): ConfigEntry {
        return ConfigEntry(
            path = toPathOrNull() ?: error("etcd key is outside config prefix: ${key.toString(UTF_8)}"),
            bytes = value.bytes.copyOf(),
            revision = toRevision(),
        )
    }

    private fun KeyValue.toEntryOrNull(): ConfigEntry? {
        val path = toPathOrNull() ?: return null
        return ConfigEntry(path, value.bytes.copyOf(), toRevision())
    }

    private fun KeyValue.toPathOrNull(): ConfigPath? {
        val rawKey = key.toString(UTF_8)
        if (!rawKey.startsWith(normalizedPrefix)) {
            return null
        }
        val value = rawKey.removePrefix(normalizedPrefix).ifBlank { "/" }
        return configPath(value)
    }

    private fun KeyValue.toRevision(): ConfigRevision {
        return ConfigRevision(
            version = modRevision.toString(),
            metadata = mapOf(
                "createRevision" to createRevision.toString(),
                "version" to version.toString(),
                "lease" to lease.toString(),
            ),
        )
    }

    private fun matches(
        watchPath: ConfigPath,
        mode: ConfigWatchMode,
        changedPath: ConfigPath,
    ): Boolean {
        return when (mode) {
            ConfigWatchMode.Value -> changedPath == watchPath
            ConfigWatchMode.Children -> changedPath.isChildOf(watchPath)
            ConfigWatchMode.Tree -> changedPath == watchPath || changedPath.isDescendantOf(watchPath)
        }
    }

    private fun normalizePrefix(prefix: String): String {
        val trimmed = prefix.trim().trimEnd('/')
        return if (trimmed.isBlank()) "" else configPath(trimmed).value
    }
}
