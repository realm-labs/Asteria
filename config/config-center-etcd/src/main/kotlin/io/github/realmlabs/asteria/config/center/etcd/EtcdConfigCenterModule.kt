package io.github.realmlabs.asteria.config.center.etcd

import io.etcd.jetcd.Client
import io.github.realmlabs.asteria.config.center.ConfigCodec
import io.github.realmlabs.asteria.config.center.ConfigStore
import io.github.realmlabs.asteria.config.center.JacksonConfigCodec
import io.github.realmlabs.asteria.config.center.RuntimeConfigRepository
import io.github.realmlabs.asteria.core.AsteriaDsl
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext

/**
 * Installs an etcd-backed config center.
 *
 * If [EtcdConfigCenterModuleBuilder.client] is not supplied, the module creates and owns the jetcd client, then closes
 * it during module stop. If a client is injected, lifecycle ownership stays with the caller.
 */
class EtcdConfigCenterModule private constructor(
    private val options: EtcdConfigCenterModuleOptions,
) : AsteriaModule {
    override val name: String = "config-center-etcd"

    private var ownedClient: Client? = null

    override suspend fun install(context: ModuleContext) {
        val client = options.client ?: createClient()
        val store = EtcdConfigStore(client, options.keyPrefix)
        context.services.register(Client::class, client)
        context.services.register(ConfigStore::class, store)
        context.services.register(EtcdConfigStore::class, store)
        context.services.register(ConfigCodec::class, options.codec)
        context.services.register(RuntimeConfigRepository::class, RuntimeConfigRepository(store, options.codec))
    }

    override suspend fun stop(context: ModuleContext) {
        ownedClient?.close()
        ownedClient = null
    }

    private fun createClient(): Client {
        val endpoints = options.endpoints.ifEmpty { error("etcd endpoints must be configured") }
        val client = Client.builder()
            .endpoints(*endpoints.toTypedArray())
            .build()
        ownedClient = client
        return client
    }

    companion object {
        operator fun invoke(configure: EtcdConfigCenterModuleBuilder.() -> Unit = {}): EtcdConfigCenterModule {
            return EtcdConfigCenterModule(EtcdConfigCenterModuleBuilder().apply(configure).build())
        }
    }
}

data class EtcdConfigCenterModuleOptions(
    val endpoints: List<String>,
    val keyPrefix: String,
    val client: Client?,
    val codec: ConfigCodec,
)

@AsteriaDsl
class EtcdConfigCenterModuleBuilder {
    var endpoints: List<String> = emptyList()
    var keyPrefix: String = ""

    private var client: Client? = null
    private var codec: ConfigCodec = JacksonConfigCodec()

    /**
     * Convenience overload for setting etcd endpoints.
     */
    fun endpoints(vararg endpoints: String) {
        this.endpoints = endpoints.toList()
    }

    /**
     * Injects an already constructed jetcd client.
     */
    fun client(client: Client) {
        this.client = client
    }

    /**
     * Overrides the payload codec used by [RuntimeConfigRepository].
     */
    fun codec(codec: ConfigCodec) {
        this.codec = codec
    }

    internal fun build(): EtcdConfigCenterModuleOptions {
        return EtcdConfigCenterModuleOptions(
            endpoints = endpoints,
            keyPrefix = keyPrefix,
            client = client,
            codec = codec,
        )
    }
}
