package io.github.realmlabs.asteria.cluster.config

import io.github.realmlabs.asteria.config.center.RuntimeConfigRepository
import io.github.realmlabs.asteria.core.AsteriaDsl
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext

/**
 * Installs the [ClusterTopologyProvider] used by cluster startup modules.
 *
 * By default the provider reads topology from the runtime config repository. Tests or embedded deployments can inject a
 * provider directly through [ClusterConfigModuleBuilder.provider].
 */
class ClusterConfigModule private constructor(
    private val options: ClusterConfigModuleOptions,
) : AsteriaModule {
    override val name: String = "cluster-config"

    override suspend fun install(context: ModuleContext) {
        val provider = options.provider ?: ConfigCenterClusterTopologyProvider(
            repository = context.services.get(RuntimeConfigRepository::class),
            layout = options.layout,
        )
        context.services.register(ClusterTopologyProvider::class, provider)
    }

    companion object {
        operator fun invoke(configure: ClusterConfigModuleBuilder.() -> Unit = {}): ClusterConfigModule {
            return ClusterConfigModule(ClusterConfigModuleBuilder().apply(configure).build())
        }
    }
}

/**
 * Resolved options for [ClusterConfigModule].
 */
data class ClusterConfigModuleOptions(
    val layout: ClusterConfigLayout,
    val provider: ClusterTopologyProvider?,
)

/**
 * DSL for configuring cluster topology lookup.
 */
@AsteriaDsl
class ClusterConfigModuleBuilder {
    var layout: ClusterConfigLayout = ClusterConfigLayout.default("asteria")
    private var provider: ClusterTopologyProvider? = null

    fun provider(provider: ClusterTopologyProvider) {
        this.provider = provider
    }

    internal fun build(): ClusterConfigModuleOptions {
        return ClusterConfigModuleOptions(layout, provider)
    }
}
