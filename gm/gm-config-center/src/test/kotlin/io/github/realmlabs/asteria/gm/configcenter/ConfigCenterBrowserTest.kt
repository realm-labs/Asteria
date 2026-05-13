package io.github.realmlabs.asteria.gm.configcenter

import io.github.realmlabs.asteria.config.center.*
import io.github.realmlabs.asteria.gm.core.discoverGmFeatures
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ConfigCenterBrowserTest {
    @Test
    fun `allowed roots block entries outside readable boundary`() = runBlocking {
        val browser = ConfigCenterBrowser(
            store = InMemoryConfigStore(),
            accessPolicy = ConfigCenterBrowserAccessPolicy(listOf(configPath("/antares"))),
        )

        assertFailsWith<ConfigCenterBrowserAccessException> {
            browser.entry("/secret")
        }
    }

    @Test
    fun `deny patterns block sensitive paths`() = runBlocking {
        val browser = ConfigCenterBrowser(
            store = InMemoryConfigStore(),
            accessPolicy = ConfigCenterBrowserAccessPolicy.fromStrings(
                allowedRoots = listOf("/"),
                denyPatterns = listOf("(?i)secret"),
            ),
        )

        assertFailsWith<ConfigCenterBrowserAccessException> {
            browser.tree("/antares/secret")
        }
    }

    @Test
    fun `tree lists direct children only`() = runBlocking {
        val store = InMemoryConfigStore()
        store.put(configPath("/antares/a"), "a".encodeToByteArray())
        store.put(configPath("/antares/b"), "b".encodeToByteArray())
        store.put(configPath("/antares/b/deep"), "deep".encodeToByteArray())
        val browser = ConfigCenterBrowser(store)

        val tree = browser.tree("/antares")

        assertFalse(tree.exists)
        assertEquals(listOf("/antares/a", "/antares/b"), tree.children.map { it.path })
    }

    @Test
    fun `entry returns existing and missing responses`() = runBlocking {
        val store = InMemoryConfigStore()
        store.put(configPath("/antares/app"), "ok".encodeToByteArray())
        val browser = ConfigCenterBrowser(store)

        val existing = browser.entry("/antares/app")
        val missing = browser.entry("/antares/missing")

        assertTrue(existing.exists)
        assertEquals("ok", existing.preview)
        assertNotNull(existing.checksum)
        assertFalse(missing.exists)
        assertEquals(null, missing.preview)
    }

    @Test
    fun `text and json entries produce utf8 preview`() = runBlocking {
        val store = InMemoryConfigStore()
        store.put(configPath("/antares/readme"), "hello".encodeToByteArray())
        store.put(configPath("/antares/data.json"), """{"enabled":true}""".encodeToByteArray())
        val browser = ConfigCenterBrowser(store)

        val text = browser.entry("/antares/readme")
        val json = browser.entry("/antares/data.json")

        assertEquals("text/plain", text.contentType)
        assertEquals("utf-8", text.encoding)
        assertEquals("application/json", json.contentType)
        assertEquals("""{"enabled":true}""", json.preview)
    }

    @Test
    fun `binary entries fall back to hash and base64 preview`() = runBlocking {
        val store = InMemoryConfigStore()
        store.put(configPath("/antares/blob"), byteArrayOf(0, 1, 2, 3, 4))
        val browser = ConfigCenterBrowser(store)

        val entry = browser.entry("/antares/blob")

        assertEquals("application/octet-stream", entry.contentType)
        assertEquals("base64", entry.encoding)
        assertEquals("AAECAwQ=", entry.preview)
        assertNotNull(entry.checksum)
    }

    @Test
    fun `preview truncates oversized text`() = runBlocking {
        val store = InMemoryConfigStore()
        store.put(configPath("/antares/large"), "abcdef".encodeToByteArray())
        val browser = ConfigCenterBrowser(store, previewLimitBytes = 3)

        val entry = browser.entry("/antares/large")

        assertEquals("abc", entry.preview)
        assertTrue(entry.truncated)
    }

    @Test
    fun `decoder exception falls back to built in preview`() = runBlocking {
        val store = InMemoryConfigStore()
        store.put(configPath("/antares/value"), "safe".encodeToByteArray())
        val browser = ConfigCenterBrowser(
            store = store,
            decoders = listOf(
                object : ConfigEntryDecoder {
                    override val id: String = "test.broken"

                    override fun supports(context: ConfigEntryDecodeContext): Boolean = true

                    override fun decode(context: ConfigEntryDecodeContext): ConfigEntryPreview {
                        error("broken decoder")
                    }
                },
            ),
        )

        val entry = browser.entry("/antares/value")

        assertEquals("text/plain", entry.contentType)
        assertEquals("safe", entry.preview)
    }

    @Test
    fun `store failures are exposed as unavailable causes`() = runBlocking {
        val cause = IllegalStateException("zk no auth")
        val browser = ConfigCenterBrowser(FailingReadStore(cause))

        val error = assertFailsWith<ConfigCenterBrowserUnavailableException> {
            browser.entry("/antares/value")
        }

        assertEquals(cause, error.cause)
    }

    @Test
    fun `custom decoder can override built in preview`() = runBlocking {
        val store = InMemoryConfigStore()
        store.put(configPath("/typed/value"), "raw".encodeToByteArray())
        val browser = ConfigCenterBrowser(
            store = store,
            decoders = listOf(
                object : ConfigEntryDecoder {
                    override val id: String = "test.typed"

                    override fun supports(context: ConfigEntryDecodeContext): Boolean {
                        return context.path == ConfigPath("/typed/value")
                    }

                    override fun decode(context: ConfigEntryDecodeContext): ConfigEntryPreview {
                        return ConfigEntryPreview("application/x-typed", "json", """{"typed":true}""")
                    }
                },
            ),
        )

        val entry = browser.entry("/typed/value")

        assertEquals("application/x-typed", entry.contentType)
        assertEquals("""{"typed":true}""", entry.preview)
    }

    @Test
    fun `feature is discoverable through service loader`() {
        val features = discoverGmFeatures()

        assertTrue(features.any { it.descriptor.id.value == "asteria.config-center" })
    }

    private class FailingReadStore(
        private val error: RuntimeException,
    ) : ConfigStore {
        override suspend fun get(path: ConfigPath): ConfigEntry? {
            throw error
        }

        override suspend fun children(path: ConfigPath): List<ConfigEntry> {
            throw error
        }

        override fun watch(path: ConfigPath, mode: ConfigWatchMode): ConfigWatch {
            return object : ConfigWatch {
                override val events = emptyFlow<io.github.realmlabs.asteria.config.center.ConfigEvent>()

                override fun close() = Unit
            }
        }

        override suspend fun put(
            path: ConfigPath,
            bytes: ByteArray,
            expectedRevision: ConfigRevision?,
        ): ConfigRevision {
            error("not supported")
        }

        override suspend fun delete(path: ConfigPath, expectedRevision: ConfigRevision?) {
            error("not supported")
        }
    }
}
