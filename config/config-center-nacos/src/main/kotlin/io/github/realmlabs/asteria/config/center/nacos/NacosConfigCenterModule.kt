package io.github.realmlabs.asteria.config.center.nacos

import com.alibaba.nacos.api.NacosFactory
import com.alibaba.nacos.api.PropertyKeyConst
import com.alibaba.nacos.api.config.ConfigService
import io.github.realmlabs.asteria.config.center.ConfigCodec
import io.github.realmlabs.asteria.config.center.ConfigStore
import io.github.realmlabs.asteria.config.center.JacksonConfigCodec
import io.github.realmlabs.asteria.config.center.RuntimeConfigRepository
import io.github.realmlabs.asteria.core.AsteriaDsl
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import java.util.*

/**
 * Installs a Nacos-backed config center.
 *
 * If [NacosConfigCenterModuleBuilder.configService] is not supplied, the module creates and owns a Nacos client, then
 * shuts it down during module stop. If a client is injected, ownership stays with the caller.
 */
class NacosConfigCenterModule private constructor(
    private val options: NacosConfigCenterModuleOptions,
) : AsteriaModule {
    override val name: String = "config-center-nacos"

    private var ownedConfigService: ConfigService? = null

    override suspend fun install(context: ModuleContext) {
        val configService = options.configService ?: createConfigService()
        val store = NacosConfigStore(
            configService = configService,
            group = options.group,
            timeoutMs = options.timeoutMs,
            dataIdPrefix = options.dataIdPrefix,
        )
        context.services.register(ConfigService::class, configService)
        context.services.register(ConfigStore::class, store)
        context.services.register(NacosConfigStore::class, store)
        context.services.register(ConfigCodec::class, options.codec)
        context.services.register(RuntimeConfigRepository::class, RuntimeConfigRepository(store, options.codec))
    }

    override suspend fun stop(context: ModuleContext) {
        ownedConfigService?.shutDown()
        ownedConfigService = null
    }

    private fun createConfigService(): ConfigService {
        val serverAddr = options.serverAddr ?: error("nacos serverAddr must be configured")
        val properties = Properties()
        properties[PropertyKeyConst.SERVER_ADDR] = serverAddr
        options.namespace?.let { properties[PropertyKeyConst.NAMESPACE] = it }
        options.username?.let { properties[PropertyKeyConst.USERNAME] = it }
        options.password?.let { properties[PropertyKeyConst.PASSWORD] = it }
        properties.putAll(options.properties)
        return NacosFactory.createConfigService(properties).also {
            ownedConfigService = it
        }
    }

    companion object {
        operator fun invoke(configure: NacosConfigCenterModuleBuilder.() -> Unit = {}): NacosConfigCenterModule {
            return NacosConfigCenterModule(NacosConfigCenterModuleBuilder().apply(configure).build())
        }
    }
}

data class NacosConfigCenterModuleOptions(
    val serverAddr: String?,
    val namespace: String?,
    val username: String?,
    val password: String?,
    val group: String,
    val timeoutMs: Long,
    val dataIdPrefix: String,
    val properties: Properties,
    val configService: ConfigService?,
    val codec: ConfigCodec,
)

/**
 * Builder for [NacosConfigCenterModule].
 */
@AsteriaDsl
class NacosConfigCenterModuleBuilder {
    var serverAddr: String? = null
    var namespace: String? = null
    var username: String? = null
    var password: String? = null
    var group: String = NacosConfigStore.DEFAULT_GROUP
    var timeoutMs: Long = NacosConfigStore.DEFAULT_TIMEOUT_MS
    var dataIdPrefix: String = NacosConfigStore.DEFAULT_DATA_ID_PREFIX

    private val properties = Properties()
    private var configService: ConfigService? = null
    private var codec: ConfigCodec = JacksonConfigCodec()

    /**
     * Adds or overrides a raw Nacos client property before client construction.
     */
    fun property(
        key: String,
        value: String,
    ) {
        properties[key] = value
    }

    /**
     * Merges multiple raw Nacos client properties before client construction.
     */
    fun properties(properties: Properties) {
        this.properties.putAll(properties)
    }

    /**
     * Injects an already constructed Nacos client.
     *
     * When set, the module does not create or shut down the client.
     */
    fun configService(configService: ConfigService) {
        this.configService = configService
    }

    /**
     * Overrides the payload codec used by [RuntimeConfigRepository].
     */
    fun codec(codec: ConfigCodec) {
        this.codec = codec
    }

    internal fun build(): NacosConfigCenterModuleOptions {
        return NacosConfigCenterModuleOptions(
            serverAddr = serverAddr,
            namespace = namespace,
            username = username,
            password = password,
            group = group,
            timeoutMs = timeoutMs,
            dataIdPrefix = dataIdPrefix,
            properties = Properties().also { it.putAll(properties) },
            configService = configService,
            codec = codec,
        )
    }
}
