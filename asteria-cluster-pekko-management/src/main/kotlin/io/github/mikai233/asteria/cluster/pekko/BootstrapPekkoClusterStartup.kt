package io.github.mikai233.asteria.cluster.pekko

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.mikai233.asteria.core.ModuleContext
import io.github.mikai233.asteria.core.RoleKey
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.javadsl.PekkoManagement

/**
 * Starts Pekko Management and Cluster Bootstrap after the ActorSystem is created.
 *
 * The concrete discovery method is supplied through config, so applications can use Kubernetes,
 * DNS, or any Pekko Discovery implementation without depending on a specific adapter module.
 */
class BootstrapPekkoClusterStartup(
    private val roles: Set<RoleKey>,
    private val config: Config = ConfigFactory.empty(),
) : PekkoClusterStartup {
    override suspend fun resolve(context: ModuleContext): PekkoClusterStartPlan {
        return PekkoClusterStartPlan(
            config = bootstrapRuntimeConfig(roles)
                .withFallback(config)
                .withFallback(ConfigFactory.load()),
            roles = roles,
            join = PekkoClusterJoin.Bootstrap,
        )
    }

    override suspend fun afterActorSystemCreated(
        context: ModuleContext,
        system: ActorSystem,
        plan: PekkoClusterStartPlan,
    ) {
        PekkoManagement.get(system).start().await()
        ClusterBootstrap.get(system).start()
    }
}

internal fun bootstrapRuntimeConfig(roles: Set<RoleKey>): Config {
    return ConfigFactory.parseMap(
        mapOf(
            "pekko.actor.provider" to "cluster",
            "pekko.cluster.roles" to roles.map { it.value },
            "pekko.cluster.seed-nodes" to emptyList<String>(),
            "pekko.cluster.jmx.multi-mbeans-in-same-jvm" to "on",
        ),
    )
}
