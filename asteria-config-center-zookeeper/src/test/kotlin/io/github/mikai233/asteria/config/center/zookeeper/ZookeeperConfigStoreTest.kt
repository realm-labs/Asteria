package io.github.mikai233.asteria.config.center.zookeeper

import io.github.mikai233.asteria.config.center.ConfigEvent
import io.github.mikai233.asteria.config.center.ConfigCodec
import io.github.mikai233.asteria.config.center.ConfigRevision
import io.github.mikai233.asteria.config.center.ConfigRevisionMismatchException
import io.github.mikai233.asteria.config.center.ConfigStore
import io.github.mikai233.asteria.config.center.ConfigWatchMode
import io.github.mikai233.asteria.config.center.RuntimeConfigRepository
import io.github.mikai233.asteria.config.center.configPath
import io.github.mikai233.asteria.core.gameApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.apache.curator.x.async.AsyncCuratorFramework
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ZookeeperConfigStoreTest {
    @Test
    fun `store supports get children watch and revision check`() = runBlocking {
        withZookeeper { client ->
            val store = ZookeeperConfigStore(AsyncCuratorFramework.wrap(client))
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
                store.put(child, "two".encodeToByteArray(), ConfigRevision("999"))
            }

            store.put(child, "two".encodeToByteArray(), revision)
            assertEquals("two", store.get(child)?.bytes?.decodeToString())

            watch.close()
        }
    }

    @Test
    fun `module registers zookeeper store and repository`() = runBlocking {
        withZookeeper { client ->
            val app = gameApplication {
                install(
                    ZookeeperConfigCenterModule {
                        client(AsyncCuratorFramework.wrap(client))
                    },
                )
            }

            app.launch()

            assertNotNull(app.services.get<ConfigStore>())
            assertNotNull(app.services.get<ZookeeperConfigStore>())
            assertNotNull(app.services.get<ConfigCodec>())
            assertNotNull(app.services.get<RuntimeConfigRepository>())

            app.stop()
        }
    }

    private suspend fun withZookeeper(block: suspend (CuratorFramework) -> Unit) {
        TestingServer().use { server ->
            val client = CuratorFrameworkFactory.newClient(
                server.connectString,
                ExponentialBackoffRetry(100, 3),
            )
            client.start()
            try {
                block(client)
            } finally {
                client.close()
            }
        }
    }
}
