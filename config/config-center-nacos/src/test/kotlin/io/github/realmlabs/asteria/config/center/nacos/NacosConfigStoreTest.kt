package io.github.realmlabs.asteria.config.center.nacos

import com.alibaba.nacos.api.config.ConfigQueryResult
import com.alibaba.nacos.api.config.ConfigService
import com.alibaba.nacos.api.config.filter.IConfigFilter
import com.alibaba.nacos.api.config.listener.FuzzyWatchEventWatcher
import com.alibaba.nacos.api.config.listener.Listener
import io.github.realmlabs.asteria.config.center.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class NacosConfigStoreTest {
    @Test
    fun `store supports get children watch and revision check`() = runBlocking {
        val configService = FakeConfigService()
        val store = NacosConfigStore(configService)
        val root = configPath("/asteria/nodes")
        val child = root / "player-1"
        val watch = store.watch(root, ConfigWatchMode.Children)
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            watch.events.first()
        }

        val revision = store.put(child, "one".encodeToByteArray())

        assertEquals("one", store.get(child)?.bytes?.decodeToString())
        assertEquals(listOf(child), store.children(root).map { it.path })
        assertIs<ConfigEvent.Upserted>(event.await())

        assertFailsWith<ConfigRevisionMismatchException> {
            store.put(child, "two".encodeToByteArray(), ConfigRevision("missing"))
        }

        store.put(child, "two".encodeToByteArray(), revision)
        assertEquals("two", store.get(child)?.bytes?.decodeToString())
        watch.close()
    }

    @Test
    fun `watch fails when listener refresh cannot read config`() = runBlocking {
        supervisorScope {
            val configService = FakeConfigService()
            val store = NacosConfigStore(configService)
            val path = configPath("/nodes/player-1")
            val watch = store.watch(path, ConfigWatchMode.Value)
            val event = async(start = CoroutineStart.UNDISPATCHED) {
                watch.events.first()
            }
            val failure = IllegalStateException("nacos unavailable")

            configService.readFailure = failure
            configService.publishConfig("asteria/nodes/player-1", "DEFAULT_GROUP", "one")

            assertEquals(failure.message, assertFailsWith<IllegalStateException> { event.await() }.message)
            watch.close()
        }
    }
}

private class FakeConfigService : ConfigService {
    private val values = ConcurrentHashMap<Pair<String, String>, String>()
    private val listeners = ConcurrentHashMap<Pair<String, String>, MutableSet<Listener>>()
    var readFailure: RuntimeException? = null

    override fun getConfig(
        dataId: String,
        group: String,
        timeoutMs: Long,
    ): String? {
        return values[dataId to group]
    }

    override fun getConfigWithResult(
        dataId: String,
        group: String,
        timeoutMs: Long,
    ): ConfigQueryResult {
        readFailure?.let { throw it }
        val content = getConfig(dataId, group, timeoutMs)
        return ConfigQueryResult(content, content?.let(::md5))
    }

    override fun getConfigAndSignListener(
        dataId: String,
        group: String,
        timeoutMs: Long,
        listener: Listener,
    ): String? {
        addListener(dataId, group, listener)
        return getConfig(dataId, group, timeoutMs)
    }

    override fun addListener(
        dataId: String,
        group: String,
        listener: Listener,
    ) {
        listeners.computeIfAbsent(dataId to group) { mutableSetOf() }.add(listener)
    }

    override fun publishConfig(
        dataId: String,
        group: String,
        content: String,
    ): Boolean {
        values[dataId to group] = content
        notify(dataId, group, content)
        return true
    }

    override fun publishConfig(
        dataId: String,
        group: String,
        content: String,
        type: String,
    ): Boolean {
        return publishConfig(dataId, group, content)
    }

    override fun publishConfigCas(
        dataId: String,
        group: String,
        content: String,
        casMd5: String,
    ): Boolean {
        val current = values[dataId to group] ?: return false
        if (md5(current) != casMd5) {
            return false
        }
        return publishConfig(dataId, group, content)
    }

    override fun publishConfigCas(
        dataId: String,
        group: String,
        content: String,
        casMd5: String,
        type: String,
    ): Boolean {
        return publishConfigCas(dataId, group, content, casMd5)
    }

    override fun removeConfig(
        dataId: String,
        group: String,
    ): Boolean {
        values.remove(dataId to group)
        notify(dataId, group, null)
        return true
    }

    override fun removeListener(
        dataId: String,
        group: String,
        listener: Listener,
    ) {
        listeners[dataId to group]?.remove(listener)
    }

    override fun getServerStatus(): String = "UP"

    override fun addConfigFilter(configFilter: IConfigFilter) {
    }

    override fun shutDown() {
    }

    override fun fuzzyWatch(
        groupNamePattern: String,
        watcher: FuzzyWatchEventWatcher,
    ) {
        throw UnsupportedOperationException()
    }

    override fun fuzzyWatch(
        dataIdPattern: String,
        groupNamePattern: String,
        watcher: FuzzyWatchEventWatcher,
    ) {
        throw UnsupportedOperationException()
    }

    override fun fuzzyWatchWithGroupKeys(
        groupNamePattern: String,
        watcher: FuzzyWatchEventWatcher,
    ): Future<Set<String>> {
        return CompletableFuture.completedFuture(emptySet())
    }

    override fun fuzzyWatchWithGroupKeys(
        dataIdPattern: String,
        groupNamePattern: String,
        watcher: FuzzyWatchEventWatcher,
    ): Future<Set<String>> {
        return CompletableFuture.completedFuture(emptySet())
    }

    override fun cancelFuzzyWatch(
        groupNamePattern: String,
        watcher: FuzzyWatchEventWatcher,
    ) {
    }

    override fun cancelFuzzyWatch(
        dataIdPattern: String,
        groupNamePattern: String,
        watcher: FuzzyWatchEventWatcher,
    ) {
    }

    private fun notify(
        dataId: String,
        group: String,
        content: String?,
    ) {
        listeners[dataId to group]?.forEach { listener ->
            listener.receiveConfigInfo(content)
        }
    }

    private fun md5(content: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(content.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
