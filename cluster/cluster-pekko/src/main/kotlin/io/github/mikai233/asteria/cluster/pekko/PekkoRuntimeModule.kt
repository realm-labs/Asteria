package io.github.mikai233.asteria.cluster.pekko

import io.github.mikai233.asteria.cluster.config.ClusterTopology
import io.github.mikai233.asteria.cluster.config.RuntimeNodeConfig
import io.github.mikai233.asteria.core.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.sharding.ShardCoordinator
import org.apache.pekko.cluster.sharding.ShardRegion
import scala.jdk.javaapi.FutureConverters
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Supplier

class PekkoRuntimeModule(
    private val startup: PekkoClusterStartup,
) : AsteriaModule {
    override val name: String = "pekko-runtime"

    private var runtime: PekkoRuntime? = null

    override suspend fun install(context: ModuleContext) {
        val plan = startup.resolve(context)
        val system = ActorSystem.create(context.name, plan.config)
        try {
            (context.runtime as? AsteriaApplication)?.setNodeRoles(plan.roles)
            startup.afterActorSystemCreated(context, system, plan)
            registerCoordinatedShutdown(context, system)
            applyJoin(plan.join, system)
            val pekkoRuntime = PekkoRuntime(system, plan.node, plan.topology)
            runtime = pekkoRuntime
            context.services.register(PekkoRuntime::class, pekkoRuntime)
            context.services.register(ActorSystem::class, system)
            plan.node?.let { context.services.register(RuntimeNodeConfig::class, it) }
            plan.topology?.let { context.services.register(ClusterTopology::class, it) }
            context.services.register(EntityShardRegistry::class, EntityShardRegistry())
            context.services.register(SingletonActorRegistry::class, SingletonActorRegistry())
        } catch (failure: Throwable) {
            FutureConverters.asJava(system.terminate()).await()
            throw failure
        }
    }

    private fun registerCoordinatedShutdown(
        context: ModuleContext,
        system: ActorSystem,
    ) {
        val lifecycle = context.services.find<AsteriaModuleLifecycle>() ?: return
        CoordinatedShutdown.get(system).addTask(
            CoordinatedShutdown.PhaseBeforeActorSystemTerminate(),
            "asteria-stop-modules-after-$name",
            Supplier { stopApplicationModulesAfterPekkoRuntime(lifecycle) },
        )
    }

    private fun stopApplicationModulesAfterPekkoRuntime(lifecycle: AsteriaModuleLifecycle): CompletionStage<Done> {
        return CompletableFuture.supplyAsync {
            runBlocking {
                lifecycle.stopAfter(name)
            }
            Done.done()
        }
    }

    private fun applyJoin(
        join: PekkoClusterJoin,
        system: ActorSystem,
    ) {
        when (join) {
            PekkoClusterJoin.Self -> {
                val cluster = Cluster.get(system)
                cluster.join(cluster.selfAddress())
            }

            PekkoClusterJoin.SeedNodes,
            PekkoClusterJoin.Bootstrap,
            PekkoClusterJoin.External,
                -> Unit
        }
    }

    override suspend fun start(context: ModuleContext) {
        val system = context.services.get<ActorSystem>()
        startEntities(context, system, context.roles)
        startSingletons(context, system, context.roles)
    }

    override suspend fun stop(context: ModuleContext) {
        val system = runtime?.system ?: return
        FutureConverters.asJava(system.terminate()).await()
        runtime = null
    }

    private fun startEntities(
        context: ModuleContext,
        system: ActorSystem,
        nodeRoles: Set<RoleKey>,
    ) {
        val registry = context.services.get<EntityShardRegistry>()
        context.entities.forEach { spec ->
            val extractor = spec.extractor() ?: PekkoShardExtractors.shardMessageByEntityIdHash(spec.shardCount)
            // Entity startup is explicit: Auto follows role ownership, Region fails on role mismatch,
            // and Proxy always starts a proxy even if this node owns the role.
            val ref = when (spec.shardStartup()) {
                PekkoShardStartup.Auto -> {
                    if (spec.role == null || spec.role in nodeRoles) {
                        startEntityRegion(context, system, spec, extractor)
                    } else {
                        startEntityProxy(system, spec, extractor)
                    }
                }

                PekkoShardStartup.Region -> {
                    require(spec.role == null || spec.role in nodeRoles) {
                        "entity ${spec.kind} requires role ${spec.role}, but this node has roles $nodeRoles"
                    }
                    startEntityRegion(context, system, spec, extractor)
                }

                PekkoShardStartup.Proxy -> startEntityProxy(system, spec, extractor)
            }
            registry.register(spec.kind, ref)
        }
    }

    private fun startEntityRegion(
        context: ModuleContext,
        system: ActorSystem,
        spec: EntitySpec<*>,
        extractor: ShardRegion.MessageExtractor,
    ): ActorRef {
        val propsFactory =
            spec.propsFactory() ?: error("entity ${spec.kind} requires actor props to start shard region")
        val strategy = spec.allocationStrategy() ?: ShardCoordinator.LeastShardAllocationStrategy(1, 10)
        return system.startAsteriaSharding(
            spec = spec,
            props = propsFactory(context.runtime, spec),
            extractor = extractor,
            strategy = strategy,
        )
    }

    private fun startEntityProxy(
        system: ActorSystem,
        spec: EntitySpec<*>,
        extractor: ShardRegion.MessageExtractor,
    ): ActorRef {
        return system.startAsteriaShardingProxy(spec.kind.value, spec.role, extractor)
    }

    private fun startSingletons(
        context: ModuleContext,
        system: ActorSystem,
        nodeRoles: Set<RoleKey>,
    ) {
        val registry = context.services.get<SingletonActorRegistry>()
        context.singletons.forEach { spec ->
            // Singleton startup controls whether this node hosts the manager. The application-facing
            // ref is always a proxy, including on nodes that host the singleton manager.
            when (spec.singletonStartup()) {
                PekkoSingletonStartup.Auto -> {
                    if (spec.role in nodeRoles) {
                        startSingletonHost(context, system, spec)
                    }
                }

                PekkoSingletonStartup.Host -> {
                    require(spec.role in nodeRoles) {
                        "singleton ${spec.name} requires role ${spec.role}, but this node has roles $nodeRoles"
                    }
                    startSingletonHost(context, system, spec)
                }

                PekkoSingletonStartup.Proxy -> Unit
            }
            val ref = startSingletonProxy(system, spec)
            registry.register(spec.name, ref)
        }
    }

    private fun startSingletonHost(
        context: ModuleContext,
        system: ActorSystem,
        spec: SingletonSpec,
    ): ActorRef {
        val propsFactory = spec.propsFactory() ?: error("singleton ${spec.name} requires actor props to start host")
        return system.startAsteriaSingleton(spec, propsFactory(context.runtime, spec))
    }

    private fun startSingletonProxy(
        system: ActorSystem,
        spec: SingletonSpec,
    ): ActorRef {
        return system.startAsteriaSingletonProxy(spec.name.value, spec.role)
    }
}
