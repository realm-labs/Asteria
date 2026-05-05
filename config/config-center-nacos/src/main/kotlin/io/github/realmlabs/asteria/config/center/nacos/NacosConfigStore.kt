package io.github.realmlabs.asteria.config.center.nacos

import com.alibaba.nacos.api.config.ConfigQueryResult
import com.alibaba.nacos.api.config.ConfigService
import com.alibaba.nacos.api.config.listener.Listener
import io.github.realmlabs.asteria.config.center.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Nacos-backed [ConfigStore].
 *
 * Because Nacos does not expose a native hierarchical tree abstraction, this adapter represents each config entry as a
 * `dataId` and maintains an extra `__children` index entry per directory-like path. As a consequence:
 * - [ConfigWatchMode.Children] is implemented by watching the index plus direct child entries.
 * - [ConfigWatchMode.Tree] currently behaves the same as [ConfigWatchMode.Children], so deeper descendants are not
 *   discovered recursively from a single watch root.
 * - revisions come from Nacos `md5` when available, otherwise from a SHA-256 hash of the payload bytes.
 */
class NacosConfigStore(
    private val configService: ConfigService,
    private val group: String = DEFAULT_GROUP,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    dataIdPrefix: String = DEFAULT_DATA_ID_PREFIX,
) : ConfigStore {
    private val normalizedDataIdPrefix = normalizeDataIdPrefix(dataIdPrefix)

    override suspend fun get(path: ConfigPath): ConfigEntry? {
        return getBlocking(path)
    }

    override suspend fun children(path: ConfigPath): List<ConfigEntry> {
        return childNames(path)
            .mapNotNull { child -> getBlocking(path / child) }
            .sortedBy { it.path.value }
    }

    override fun watch(
        path: ConfigPath,
        mode: ConfigWatchMode,
    ): ConfigWatch {
        return when (mode) {
            ConfigWatchMode.Value -> watchValue(path)
            ConfigWatchMode.Children -> watchChildren(path)
            ConfigWatchMode.Tree -> watchTree(path)
        }
    }

    override suspend fun put(
        path: ConfigPath,
        bytes: ByteArray,
        expectedRevision: ConfigRevision?,
    ): ConfigRevision {
        val current = getResult(path)
        if (expectedRevision != null && expectedRevision.version != current?.revision?.version) {
            throw ConfigRevisionMismatchException(path, expectedRevision, current?.revision)
        }

        val content = bytes.toString(UTF_8)
        val published = if (expectedRevision == null) {
            configService.publishConfig(dataIdOf(path), group, content)
        } else {
            configService.publishConfigCas(dataIdOf(path), group, content, expectedRevision.version)
        }
        check(published) {
            "failed to publish nacos config at $path"
        }
        publishParentIndex(path, remove = false)
        return getResult(path)?.revision ?: revisionOf(bytes)
    }

    override suspend fun delete(
        path: ConfigPath,
        expectedRevision: ConfigRevision?,
    ) {
        val current = getBlocking(path)
        if (expectedRevision != null && expectedRevision.version != current?.revision?.version) {
            throw ConfigRevisionMismatchException(path, expectedRevision, current?.revision)
        }

        configService.removeConfig(dataIdOf(path), group)
        publishParentIndex(path, remove = true)
    }

    private fun watchValue(path: ConfigPath): ConfigWatch {
        val flow = MutableSharedFlow<ConfigEvent>(extraBufferCapacity = 64)
        val listener = listenerOf {
            val entry = getBlocking(path)
            if (entry == null) {
                flow.tryEmit(ConfigEvent.Deleted(path, null))
            } else {
                flow.tryEmit(ConfigEvent.Upserted(path, entry))
            }
        }
        configService.addListener(dataIdOf(path), group, listener)
        return object : ConfigWatch {
            override val events: Flow<ConfigEvent> = flow

            override fun close() {
                configService.removeListener(dataIdOf(path), group, listener)
            }
        }
    }

    private fun watchChildren(path: ConfigPath): ConfigWatch {
        val flow = MutableSharedFlow<ConfigEvent>(extraBufferCapacity = 64)
        val childListeners = ConcurrentHashMap<String, Listener>()

        fun emitSnapshotTrigger() {
            flow.tryEmit(ConfigEvent.Upserted(path, indexEntry(path)))
        }

        fun refreshChildListeners() {
            val names = childNames(path).toSet()
            names.forEach { child ->
                childListeners.computeIfAbsent(child) {
                    listenerOf {
                        val childPath = path / child
                        val entry = getBlocking(childPath)
                        if (entry == null) {
                            flow.tryEmit(ConfigEvent.Deleted(childPath, null))
                        } else {
                            flow.tryEmit(ConfigEvent.Upserted(childPath, entry))
                        }
                    }.also { listener ->
                        configService.addListener(dataIdOf(path / child), group, listener)
                    }
                }
            }
            childListeners.keys
                .filter { it !in names }
                .forEach { removed ->
                    childListeners.remove(removed)?.let { listener ->
                        configService.removeListener(dataIdOf(path / removed), group, listener)
                    }
                }
        }

        val indexListener = listenerOf {
            refreshChildListeners()
            emitSnapshotTrigger()
        }
        refreshChildListeners()
        configService.addListener(indexDataIdOf(path), group, indexListener)

        return object : ConfigWatch {
            override val events: Flow<ConfigEvent> = flow

            override fun close() {
                configService.removeListener(indexDataIdOf(path), group, indexListener)
                childListeners.forEach { (child, listener) ->
                    configService.removeListener(dataIdOf(path / child), group, listener)
                }
                childListeners.clear()
            }
        }
    }

    private fun watchTree(path: ConfigPath): ConfigWatch {
        return watchChildren(path)
    }

    private fun getBlocking(path: ConfigPath): ConfigEntry? {
        return getResult(path)?.entry
    }

    private fun getResult(path: ConfigPath): NacosConfigResult? {
        val result = configService.getConfigWithResult(dataIdOf(path), group, timeoutMs)
        val content = result.content ?: return null
        val bytes = content.toByteArray(UTF_8)
        val revision = result.revisionOf(bytes)
        return NacosConfigResult(ConfigEntry(path, bytes, revision), revision)
    }

    private fun childNames(path: ConfigPath): List<String> {
        val content = configService.getConfig(indexDataIdOf(path), group, timeoutMs) ?: return emptyList()
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun publishParentIndex(
        path: ConfigPath,
        remove: Boolean,
    ) {
        val parent = path.parent ?: return
        val names = childNames(parent).toMutableSet()
        if (remove) {
            names.remove(path.name)
        } else {
            names.add(path.name)
        }
        configService.publishConfig(indexDataIdOf(parent), group, names.sorted().joinToString("\n"))
    }

    private fun indexEntry(path: ConfigPath): ConfigEntry {
        val bytes = childNames(path).joinToString("\n").toByteArray(UTF_8)
        return ConfigEntry(path, bytes, revisionOf(bytes))
    }

    private fun listenerOf(block: () -> Unit): Listener {
        return object : Listener {
            override fun getExecutor(): Executor? = null

            override fun receiveConfigInfo(configInfo: String?) {
                block()
            }
        }
    }

    private fun dataIdOf(path: ConfigPath): String {
        val suffix = path.value.removePrefix("/")
        return if (suffix.isBlank()) normalizedDataIdPrefix else "$normalizedDataIdPrefix/$suffix"
    }

    private fun indexDataIdOf(path: ConfigPath): String {
        return "${dataIdOf(path)}$INDEX_SUFFIX"
    }

    private fun revisionOf(bytes: ByteArray): ConfigRevision {
        return ConfigRevision(
            version = sha256(bytes),
            metadata = mapOf("algorithm" to "sha-256"),
        )
    }

    private fun ConfigQueryResult.revisionOf(bytes: ByteArray): ConfigRevision {
        val md5 = md5
        if (!md5.isNullOrBlank()) {
            return ConfigRevision(
                version = md5,
                metadata = mapOf("algorithm" to "md5"),
            )
        }
        return revisionOf(bytes)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeDataIdPrefix(prefix: String): String {
        val trimmed = prefix.trim().trim('/')
        return configPath("/${trimmed.ifBlank { DEFAULT_DATA_ID_PREFIX }}").value.removePrefix("/")
    }

    companion object {
        const val DEFAULT_GROUP: String = "DEFAULT_GROUP"
        const val DEFAULT_TIMEOUT_MS: Long = 3000
        const val DEFAULT_DATA_ID_PREFIX: String = "asteria"
        private const val INDEX_SUFFIX: String = "/__children"
    }
}

private data class NacosConfigResult(
    val entry: ConfigEntry,
    val revision: ConfigRevision,
)
