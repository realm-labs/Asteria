package io.github.mikai233.asteria.cluster.config

import io.github.mikai233.asteria.config.center.RuntimeConfigRepository
import io.github.mikai233.asteria.core.AsteriaDsl
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext

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

data class ClusterConfigModuleOptions(
    val layout: ClusterConfigLayout,
    val provider: ClusterTopologyProvider?,
)

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
