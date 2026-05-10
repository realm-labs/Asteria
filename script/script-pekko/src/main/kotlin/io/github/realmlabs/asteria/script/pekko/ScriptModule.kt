package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.cluster.config.ClusterViewService
import io.github.realmlabs.asteria.core.AsteriaDsl
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.observability.NoopTracer
import io.github.realmlabs.asteria.observability.Tracer
import io.github.realmlabs.asteria.script.*
import org.apache.pekko.actor.ActorSystem
import java.nio.file.Path

class ScriptModule private constructor(
    private val options: ScriptModuleOptions,
) : AsteriaModule {
    override val name: String = "script"

    override suspend fun install(context: ModuleContext) {
        val registry = ScriptEngineRegistry(options.engines)
        val policy = options.policy ?: DefaultScriptPolicy(
            allowNodeScripts = options.allowNodeScripts,
            allowActorScripts = options.allowActorScripts,
            allowedEngines = options.engines.mapTo(mutableSetOf()) { it.name },
            maxArtifactBytes = options.maxArtifactBytes,
        )
        val auditSink = when (options.auditSinks.size) {
            0 -> NoopScriptAuditSink
            1 -> options.auditSinks.single()
            else -> CompositeScriptAuditSink(options.auditSinks)
        }
        val executor = ScriptExecutor(registry)
        val executionStore = options.executionStore ?: InMemoryScriptExecutionStore()
        context.services.register(ScriptEngineRegistry::class, registry)
        context.services.register(ScriptExecutor::class, executor)
        context.services.register(ScriptPolicy::class, policy)
        context.services.register(ScriptAuditSink::class, auditSink)
        context.services.register(ScriptExecutionStore::class, executionStore)
        options.resourceResolver?.let { context.services.register(ScriptResourceResolver::class, it) }
        context.services.register(ScriptRunner::class, ScriptRunner(executor, policy, auditSink, executionStore))
        context.services.register(ScriptModuleOptions::class, options)
    }

    override suspend fun start(context: ModuleContext) {
        val system = context.services.get<ActorSystem>()
        val actor = system.actorOf(ScriptRuntimeActor.props(context.runtime), ScriptRuntimeActor.NAME)
        val runtime = PekkoScriptRuntime(
            actor = actor,
            system = system,
            tracer = context.services.find<Tracer>() ?: NoopTracer,
            metrics = context.services.find<Metrics>() ?: NoopMetrics,
            clusterView = context.services.find<ClusterViewService>(),
        )
        context.services.register(PekkoScriptRuntime::class, runtime)
        context.services.register(ScriptRuntime::class, runtime)
    }

    companion object {
        operator fun invoke(configure: ScriptModuleBuilder.() -> Unit): ScriptModule {
            return ScriptModule(ScriptModuleBuilder().apply(configure).build())
        }
    }
}

data class ScriptModuleOptions(
    val engines: List<ScriptEngine>,
    val allowNodeScripts: Boolean,
    val allowActorScripts: Boolean,
    val maxArtifactBytes: Int,
    val policy: ScriptPolicy?,
    val executionStore: ScriptExecutionStore?,
    val auditSinks: List<ScriptAuditSink>,
    val resourceResolver: ScriptResourceResolver?,
)

@AsteriaDsl
class ScriptModuleBuilder {
    private val engines: MutableList<ScriptEngine> = mutableListOf()
    private val auditSinks: MutableList<ScriptAuditSink> = mutableListOf()
    var allowNodeScripts: Boolean = false
    var allowActorScripts: Boolean = false
    var maxArtifactBytes: Int = 1024 * 1024
    private var policy: ScriptPolicy? = null
    private var executionStore: ScriptExecutionStore? = null
    private var resourceResolver: ScriptResourceResolver? = null

    fun engine(engine: ScriptEngine) {
        engines.add(engine)
    }

    fun policy(policy: ScriptPolicy) {
        this.policy = policy
    }

    fun executionStore(executionStore: ScriptExecutionStore) {
        this.executionStore = executionStore
    }

    fun auditSink(auditSink: ScriptAuditSink) {
        auditSinks.add(auditSink)
    }

    fun resourceResolver(resourceResolver: ScriptResourceResolver) {
        this.resourceResolver = resourceResolver
    }

    fun resourceCache(
        cacheDirectory: Path,
        downloader: ScriptResourceDownloader = DefaultScriptResourceDownloader,
    ) {
        resourceResolver(CachingScriptResourceResolver(cacheDirectory, downloader))
    }

    internal fun build(): ScriptModuleOptions {
        return ScriptModuleOptions(
            engines = engines.toList(),
            allowNodeScripts = allowNodeScripts,
            allowActorScripts = allowActorScripts,
            maxArtifactBytes = maxArtifactBytes,
            policy = policy,
            executionStore = executionStore,
            auditSinks = auditSinks.toList(),
            resourceResolver = resourceResolver,
        )
    }
}
