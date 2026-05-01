package io.github.mikai233.asteria.starter

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.mikai233.asteria.cluster.config.ClusterConfigLayout
import io.github.mikai233.asteria.cluster.config.ClusterConfigModule
import io.github.mikai233.asteria.cluster.config.ClusterTopology
import io.github.mikai233.asteria.cluster.config.StaticClusterTopologyProvider
import io.github.mikai233.asteria.cluster.pekko.PekkoRuntimeModule
import io.github.mikai233.asteria.core.AsteriaApplication
import io.github.mikai233.asteria.core.AsteriaApplicationBuilder
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import io.github.mikai233.asteria.core.gameApplication
import io.github.mikai233.asteria.message.RouteRegistry
import io.github.mikai233.asteria.message.RouteRegistryBuilder
import io.github.mikai233.asteria.rpc.RpcModule

class RouteModule(
    private val registry: RouteRegistry,
) : AsteriaModule {
    override val name: String = "message-routes"

    override suspend fun install(context: ModuleContext) {
        context.services.register(RouteRegistry::class, registry)
    }
}

fun AsteriaApplicationBuilder.routes(configure: RouteRegistryBuilder.() -> Unit) {
    install(RouteModule(RouteRegistryBuilder().apply(configure).build()))
}

fun localGameApplication(configure: AsteriaApplicationBuilder.() -> Unit): AsteriaApplication {
    return gameApplication {
        install(PekkoRuntimeModule.local())
        install(RpcModule.autoDiscover())
        configure()
    }
}

fun clusterGameApplication(
    nodeId: String,
    layout: ClusterConfigLayout? = null,
    pekkoConfig: Config = ConfigFactory.empty(),
    configure: AsteriaApplicationBuilder.() -> Unit,
): AsteriaApplication {
    return gameApplication {
        install(RpcModule.autoDiscover())
        configure()
        val applicationName = name
        install(
            ClusterConfigModule {
                this.layout = layout ?: ClusterConfigLayout.default(applicationName)
            },
        )
        install(PekkoRuntimeModule.cluster(nodeId, pekkoConfig))
    }
}

fun clusterGameApplication(
    nodeId: String,
    topology: ClusterTopology,
    pekkoConfig: Config = ConfigFactory.empty(),
    configure: AsteriaApplicationBuilder.() -> Unit,
): AsteriaApplication {
    return gameApplication {
        install(RpcModule.autoDiscover())
        configure()
        install(
            ClusterConfigModule {
                provider(StaticClusterTopologyProvider(topology))
            },
        )
        install(PekkoRuntimeModule.cluster(nodeId, pekkoConfig))
    }
}
