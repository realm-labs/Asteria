package io.github.mikai233.asteria.cluster.pekko

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import io.github.mikai233.asteria.rpc.RpcRouteRegistry
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.sharding.ShardCoordinator
import scala.jdk.javaapi.FutureConverters

class PekkoRuntimeModule private constructor(
    private val configFactory: (ModuleContext) -> Config,
    private val selfJoin: Boolean,
) : AsteriaModule {
    override val name: String = "pekko-runtime"

    private var runtime: PekkoRuntime? = null

    override suspend fun install(context: ModuleContext) {
        val config = configFactory(context)
        val system = ActorSystem.create(context.name, config)
        if (selfJoin) {
            val cluster = Cluster.get(system)
            cluster.join(cluster.selfAddress())
        }
        val pekkoRuntime = PekkoRuntime(system)
        runtime = pekkoRuntime
        context.services.register(PekkoRuntime::class, pekkoRuntime)
        context.services.register(ActorSystem::class, system)
        context.services.register(EntityShardRegistry::class, EntityShardRegistry())
        context.services.register(SingletonActorRegistry::class, SingletonActorRegistry())
    }

    override suspend fun start(context: ModuleContext) {
        val system = context.services.get<ActorSystem>()
        startEntities(context, system)
        startSingletons(context, system)
        startRpcRouter(context, system)
    }

    override suspend fun stop(context: ModuleContext) {
        val system = runtime?.system ?: return
        FutureConverters.asJava(system.terminate()).await()
        runtime = null
    }

    private fun startEntities(context: ModuleContext, system: ActorSystem) {
        val registry = context.services.get<EntityShardRegistry>()
        context.entities.forEach { spec ->
            val propsFactory = spec.propsFactory() ?: return@forEach
            val extractor = spec.extractor() ?: PekkoShardExtractors.shardMessageByEntityIdHash(spec.shardCount)
            val strategy = spec.allocationStrategy() ?: ShardCoordinator.LeastShardAllocationStrategy(1, 10)
            val ref = system.startAsteriaSharding(
                spec = spec,
                props = propsFactory(context.application, spec),
                extractor = extractor,
                strategy = strategy,
            )
            registry.register(spec.kind, ref)
        }
    }

    private fun startSingletons(context: ModuleContext, system: ActorSystem) {
        val registry = context.services.get<SingletonActorRegistry>()
        context.singletons.forEach { spec ->
            val propsFactory = spec.propsFactory() ?: return@forEach
            val ref = system.startAsteriaSingleton(spec, propsFactory(context.application, spec))
            registry.register(spec.name, ref)
        }
    }

    private fun startRpcRouter(context: ModuleContext, system: ActorSystem) {
        val routeRegistry = context.services.find<RpcRouteRegistry>() ?: return
        val router = PekkoRpcRouter(
            system = system,
            routeRegistry = routeRegistry,
            entityShards = context.services.get(),
            singletons = context.services.get(),
        )
        context.services.register(PekkoRpcRouter::class, router)
    }

    companion object {
        fun local(config: Config = ConfigFactory.empty()): PekkoRuntimeModule {
            return PekkoRuntimeModule(
                configFactory = { context ->
                    val runtimeConfig = mapOf(
                        "pekko.actor.provider" to "cluster",
                        "pekko.remote.artery.canonical.hostname" to "127.0.0.1",
                        "pekko.remote.artery.canonical.port" to 0,
                        "pekko.cluster.roles" to context.roles.map { it.value },
                        "pekko.cluster.jmx.multi-mbeans-in-same-jvm" to "on",
                    )
                    ConfigFactory.parseMap(runtimeConfig)
                        .withFallback(config)
                        .withFallback(ConfigFactory.load())
                },
                selfJoin = true,
            )
        }

        fun fromConfig(configFactory: (ModuleContext) -> Config): PekkoRuntimeModule {
            return PekkoRuntimeModule(configFactory, selfJoin = false)
        }
    }
}
