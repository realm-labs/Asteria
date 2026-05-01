package io.github.mikai233.asteria.config.center.zookeeper

import io.github.mikai233.asteria.config.center.ConfigCodec
import io.github.mikai233.asteria.config.center.ConfigStore
import io.github.mikai233.asteria.config.center.JacksonConfigCodec
import io.github.mikai233.asteria.config.center.RuntimeConfigRepository
import io.github.mikai233.asteria.core.AsteriaDsl
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import org.apache.curator.RetryPolicy
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.x.async.AsyncCuratorFramework

class ZookeeperConfigCenterModule private constructor(
    private val options: ZookeeperConfigCenterModuleOptions,
) : AsteriaModule {
    override val name: String = "config-center-zookeeper"

    private var ownedClient: CuratorFramework? = null

    override suspend fun install(context: ModuleContext) {
        val client = options.client ?: createClient()
        val store = ZookeeperConfigStore(client)
        context.services.register(AsyncCuratorFramework::class, client)
        context.services.register(ConfigStore::class, store)
        context.services.register(ZookeeperConfigStore::class, store)
        context.services.register(ConfigCodec::class, options.codec)
        context.services.register(RuntimeConfigRepository::class, RuntimeConfigRepository(store, options.codec))
    }

    override suspend fun stop(context: ModuleContext) {
        ownedClient?.close()
        ownedClient = null
    }

    private fun createClient(): AsyncCuratorFramework {
        val connectionString = options.connectionString ?: error("zookeeper connection string must be configured")
        val client = CuratorFrameworkFactory.newClient(connectionString, options.retryPolicy)
        client.start()
        ownedClient = client
        return AsyncCuratorFramework.wrap(client)
    }

    companion object {
        operator fun invoke(configure: ZookeeperConfigCenterModuleBuilder.() -> Unit = {}): ZookeeperConfigCenterModule {
            return ZookeeperConfigCenterModule(ZookeeperConfigCenterModuleBuilder().apply(configure).build())
        }
    }
}

data class ZookeeperConfigCenterModuleOptions(
    val connectionString: String?,
    val client: AsyncCuratorFramework?,
    val retryPolicy: RetryPolicy,
    val codec: ConfigCodec,
)

@AsteriaDsl
class ZookeeperConfigCenterModuleBuilder {
    var connectionString: String? = null
    var retryPolicy: RetryPolicy = ExponentialBackoffRetry(1000, 3)

    private var client: AsyncCuratorFramework? = null
    private var codec: ConfigCodec = JacksonConfigCodec()

    fun client(client: AsyncCuratorFramework) {
        this.client = client
    }

    fun codec(codec: ConfigCodec) {
        this.codec = codec
    }

    internal fun build(): ZookeeperConfigCenterModuleOptions {
        return ZookeeperConfigCenterModuleOptions(
            connectionString = connectionString,
            client = client,
            retryPolicy = retryPolicy,
            codec = codec,
        )
    }
}
