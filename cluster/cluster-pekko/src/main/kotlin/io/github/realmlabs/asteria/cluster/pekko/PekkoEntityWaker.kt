package io.github.realmlabs.asteria.cluster.pekko

import io.github.realmlabs.asteria.config.ConfigReloadSubscription
import io.github.realmlabs.asteria.config.ConfigService
import io.github.realmlabs.asteria.core.*
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.singleton.ClusterSingletonManager
import org.apache.pekko.cluster.singleton.ClusterSingletonManagerSettings
import org.apache.pekko.cluster.singleton.ClusterSingletonProxy
import org.apache.pekko.cluster.singleton.ClusterSingletonProxySettings
import org.apache.pekko.pattern.Patterns
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Runtime context passed to entity wake target sources.
 *
 * Target sources usually read from a config-derived service, for example a service that exposes all configured world
 * ids. They should return the complete desired target set for the current revision; the waker adds only targets that
 * have not already completed in the current coordinator process.
 */
class PekkoEntityWakeContext(
    val runtime: NodeRuntime,
    val services: ServiceRegistry,
)

/**
 * Provides the current target ids for one wake task.
 */
fun interface PekkoEntityWakeTargetSource<ID : Any> {
    suspend fun targets(context: PekkoEntityWakeContext): Iterable<ID>
}

/**
 * Builds the message sent to the sharded entity for one target id.
 *
 * The produced message must route to the same entity id through the entity's Pekko message extractor.
 */
fun interface PekkoEntityWakeMessageFactory<ID : Any> {
    fun message(targetId: ID): Any
}

/**
 * Classifies an ask response from a wake message.
 *
 * Throwing from this classifier is treated the same as a failed wake attempt.
 */
fun interface PekkoEntityWakeResultClassifier {
    fun successful(response: Any): Boolean
}

/**
 * Sliding-window concurrency policy.
 *
 * The coordinator keeps up to [initial] wake asks in flight, then adjusts within [min] and [max] after every
 * [adjustmentWindow] results. Healthy windows grow by [growthStep], while failure-heavy windows shrink by [shrinkStep].
 */
data class PekkoEntityWakeConcurrency(
    val initial: Int = 20,
    val min: Int = 1,
    val max: Int = 100,
    val growthStep: Int = 7,
    val shrinkStep: Int = 10,
    val growthSuccessRate: Double = 0.8,
    val shrinkFailureRate: Double = 0.5,
    val adjustmentWindow: Int = 20,
    val cooldownWindows: Int = 2,
) {
    init {
        require(min > 0) { "wake min concurrency must be greater than zero" }
        require(max >= min) { "wake max concurrency must be greater than or equal to min" }
        require(initial in min..max) { "wake initial concurrency must be within min and max" }
        require(growthStep > 0) { "wake growth step must be greater than zero" }
        require(shrinkStep > 0) { "wake shrink step must be greater than zero" }
        require(growthSuccessRate in 0.0..1.0) { "wake growth success rate must be between 0 and 1" }
        require(shrinkFailureRate in 0.0..1.0) { "wake shrink failure rate must be between 0 and 1" }
        require(adjustmentWindow > 0) { "wake adjustment window must be greater than zero" }
        require(cooldownWindows >= 0) { "wake cooldown windows must not be negative" }
    }
}

/**
 * Retry policy for one target.
 *
 * A target gets at most [maxAttempts] short-cycle attempts before it is marked exhausted. Exhausted targets stay visible
 * in [PekkoEntityWaker.status] and are retried after [exhaustedDelay]. Set [maxAttempts] to `null` only when repeated
 * failures are cheap and safe.
 */
data class PekkoEntityWakeRetry(
    val timeout: Duration = 3.minutes,
    val initialDelay: Duration = 5.seconds,
    val maxDelay: Duration = 1.minutes,
    val backoffFactor: Double = 2.0,
    val maxAttempts: Int? = 10,
    val exhaustedDelay: Duration? = 10.minutes,
) {
    init {
        require(timeout.isPositive()) { "wake timeout must be positive" }
        require(initialDelay.isPositive()) { "wake initial retry delay must be positive" }
        require(maxDelay >= initialDelay) { "wake max retry delay must be greater than or equal to initial delay" }
        require(backoffFactor >= 1.0) { "wake retry backoff factor must be greater than or equal to 1" }
        maxAttempts?.let { require(it > 0) { "wake max attempts must be greater than zero" } }
        exhaustedDelay?.let { require(it.isPositive()) { "wake exhausted retry delay must be positive" } }
    }
}

/**
 * Cluster readiness gate checked before a task reads its source or dispatches wake messages.
 *
 * Use this to avoid waking data-heavy actors before enough shard-hosting nodes have joined. [role] narrows the member
 * set, and [minUpRatio] is the required fraction of non-removed members in `Up` state.
 */
data class PekkoEntityWakeReadiness(
    val role: RoleKey? = null,
    val minUpRatio: Double = 0.0,
) {
    init {
        require(minUpRatio in 0.0..1.0) { "wake readiness min up ratio must be between 0 and 1" }
    }
}

/**
 * One entity wake task.
 *
 * A task reads a complete desired target set from [targetSource], converts each id to a sharding message through
 * [messageFactory], and treats [resultClassifier] as the acknowledgement check. Target messages should be idempotent:
 * coordinator restarts, GM actions, or config reloads may cause the same id to be woken again.
 */
data class PekkoEntityWakeTask<ID : Any>(
    val name: String,
    val entityKind: EntityKind,
    val targetSource: PekkoEntityWakeTargetSource<ID>,
    val messageFactory: PekkoEntityWakeMessageFactory<ID>,
    val resultClassifier: PekkoEntityWakeResultClassifier = PekkoEntityWakeResultClassifier { true },
    val concurrency: PekkoEntityWakeConcurrency = PekkoEntityWakeConcurrency(),
    val retry: PekkoEntityWakeRetry = PekkoEntityWakeRetry(),
    val readiness: PekkoEntityWakeReadiness = PekkoEntityWakeReadiness(),
) {
    init {
        require(name.isNotBlank()) { "wake task name must not be blank" }
    }

    internal fun erased(): ErasedPekkoEntityWakeTask {
        return ErasedPekkoEntityWakeTask(
            name = name,
            entityKind = entityKind,
            targetSource = { context -> targetSource.targets(context).map { it as Any } },
            messageFactory = { targetId ->
                @Suppress("UNCHECKED_CAST")
                messageFactory.message(targetId as ID)
            },
            resultClassifier = resultClassifier,
            concurrency = concurrency,
            retry = retry,
            readiness = readiness,
        )
    }
}

/**
 * DSL for one [PekkoEntityWakeTask].
 *
 * Minimal example:
 *
 * ```kotlin
 * task<Long>("world") {
 *     kind("world")
 *     targets { services.get<GameWorldConfigService>().worldIds }
 *     message { worldId -> WorldWakeupReq(worldId) }
 *     success { response -> response is WorldWakeupResp && response.success }
 * }
 * ```
 *
 * The message returned by [message] must route to the same entity id through the entity's sharding extractor.
 */
class PekkoEntityWakeTaskBuilder<ID : Any> internal constructor(
    private val name: String,
) {
    var entityKind: EntityKind? = null
    var concurrency: PekkoEntityWakeConcurrency = PekkoEntityWakeConcurrency()
    var retry: PekkoEntityWakeRetry = PekkoEntityWakeRetry()
    var readiness: PekkoEntityWakeReadiness = PekkoEntityWakeReadiness()
    private var targetSource: PekkoEntityWakeTargetSource<ID>? = null
    private var messageFactory: PekkoEntityWakeMessageFactory<ID>? = null
    private var resultClassifier: PekkoEntityWakeResultClassifier = PekkoEntityWakeResultClassifier { true }

    /**
     * Sets the sharded entity kind registered by [PekkoRuntimeModule].
     */
    fun kind(value: String) {
        entityKind = EntityKind(value)
    }

    /**
     * Sets the source of desired target ids.
     *
     * Return the full current set, not a delta. Reconcile compares it with local wake state and queues only targets that
     * still need a wake attempt.
     */
    fun targets(source: PekkoEntityWakeTargetSource<ID>) {
        targetSource = source
    }

    /**
     * Sets the source of desired target ids using [PekkoEntityWakeContext] as receiver.
     */
    fun targets(source: suspend PekkoEntityWakeContext.() -> Iterable<ID>) {
        targetSource = PekkoEntityWakeTargetSource { context -> context.source() }
    }

    /**
     * Sets the message factory used for each target id.
     */
    fun message(factory: PekkoEntityWakeMessageFactory<ID>) {
        messageFactory = factory
    }

    /**
     * Sets the message factory used for each target id.
     */
    fun message(factory: (ID) -> Any) {
        messageFactory = PekkoEntityWakeMessageFactory(factory)
    }

    /**
     * Sets the acknowledgement classifier for `ask` responses.
     *
     * Returning `false` records a failed attempt and applies the task retry policy.
     */
    fun success(classifier: PekkoEntityWakeResultClassifier) {
        resultClassifier = classifier
    }

    /**
     * Sets the acknowledgement classifier for `ask` responses.
     */
    fun success(classifier: (Any) -> Boolean) {
        resultClassifier = PekkoEntityWakeResultClassifier(classifier)
    }

    /**
     * Configures sliding-window concurrency and adaptive growth/shrink thresholds.
     */
    fun concurrency(configure: PekkoEntityWakeConcurrencyBuilder.() -> Unit) {
        concurrency = PekkoEntityWakeConcurrencyBuilder(concurrency).apply(configure).build()
    }

    /**
     * Configures ask timeout, retry backoff, and exhausted-target cooling.
     */
    fun retry(configure: PekkoEntityWakeRetryBuilder.() -> Unit) {
        retry = PekkoEntityWakeRetryBuilder(retry).apply(configure).build()
    }

    /**
     * Configures the cluster readiness gate checked before dispatch.
     */
    fun readiness(configure: PekkoEntityWakeReadinessBuilder.() -> Unit) {
        readiness = PekkoEntityWakeReadinessBuilder(readiness).apply(configure).build()
    }

    internal fun build(): PekkoEntityWakeTask<ID> {
        val kind = requireNotNull(entityKind) { "wake task $name requires entityKind" }
        return PekkoEntityWakeTask(
            name = name,
            entityKind = kind,
            targetSource = requireNotNull(targetSource) { "wake task $name requires targets" },
            messageFactory = requireNotNull(messageFactory) { "wake task $name requires message" },
            resultClassifier = resultClassifier,
            concurrency = concurrency,
            retry = retry,
            readiness = readiness,
        )
    }
}

/**
 * DSL for [PekkoEntityWakeConcurrency].
 */
class PekkoEntityWakeConcurrencyBuilder internal constructor(
    defaults: PekkoEntityWakeConcurrency,
) {
    var initial: Int = defaults.initial
    var min: Int = defaults.min
    var max: Int = defaults.max
    var growthStep: Int = defaults.growthStep
    var shrinkStep: Int = defaults.shrinkStep
    var growthSuccessRate: Double = defaults.growthSuccessRate
    var shrinkFailureRate: Double = defaults.shrinkFailureRate
    var adjustmentWindow: Int = defaults.adjustmentWindow
    var cooldownWindows: Int = defaults.cooldownWindows

    internal fun build(): PekkoEntityWakeConcurrency {
        return PekkoEntityWakeConcurrency(
            initial = initial,
            min = min,
            max = max,
            growthStep = growthStep,
            shrinkStep = shrinkStep,
            growthSuccessRate = growthSuccessRate,
            shrinkFailureRate = shrinkFailureRate,
            adjustmentWindow = adjustmentWindow,
            cooldownWindows = cooldownWindows,
        )
    }
}

/**
 * DSL for retry policy.
 *
 * [maxAttempts] limits short-cycle retries. After that, [exhaustedDelay] keeps bad targets from constantly hitting
 * storage while still allowing automatic recovery later. GM can also call [PekkoEntityWaker.cancel] to suppress a target
 * until it is manually woken again.
 */
class PekkoEntityWakeRetryBuilder internal constructor(
    defaults: PekkoEntityWakeRetry,
) {
    var timeout: Duration = defaults.timeout
    var initialDelay: Duration = defaults.initialDelay
    var maxDelay: Duration = defaults.maxDelay
    var backoffFactor: Double = defaults.backoffFactor
    var maxAttempts: Int? = defaults.maxAttempts
    var exhaustedDelay: Duration? = defaults.exhaustedDelay

    internal fun build(): PekkoEntityWakeRetry {
        return PekkoEntityWakeRetry(
            timeout = timeout,
            initialDelay = initialDelay,
            maxDelay = maxDelay,
            backoffFactor = backoffFactor,
            maxAttempts = maxAttempts,
            exhaustedDelay = exhaustedDelay,
        )
    }
}

/**
 * DSL for the cluster readiness gate.
 */
class PekkoEntityWakeReadinessBuilder internal constructor(
    defaults: PekkoEntityWakeReadiness,
) {
    var role: RoleKey? = defaults.role
    var minUpRatio: Double = defaults.minUpRatio

    fun role(value: String) {
        role = RoleKey(value)
    }

    internal fun build(): PekkoEntityWakeReadiness {
        return PekkoEntityWakeReadiness(role, minUpRatio)
    }
}

/**
 * Options for [PekkoEntityWakerModule].
 */
data class PekkoEntityWakerOptions(
    val moduleName: String = "pekko-entity-waker",
    val singletonName: String = "asteriaEntityWaker",
    val coordinatorRole: RoleKey? = null,
    val reconcileOnStart: Boolean = true,
    val reconcileOnConfigReload: Boolean = true,
    val tasks: List<PekkoEntityWakeTask<*>>,
) {
    init {
        require(moduleName.isNotBlank()) { "entity waker module name must not be blank" }
        require(singletonName.isNotBlank()) { "entity waker singleton name must not be blank" }
        require(tasks.isNotEmpty()) { "entity waker tasks must not be empty" }
        require(tasks.map { it.name }.distinct().size == tasks.size) { "entity waker task names must be unique" }
    }
}

/**
 * DSL for [PekkoEntityWakerModule].
 *
 * Install this module after [PekkoRuntimeModule], because it uses [EntityShardRegistry] and starts a cluster singleton
 * coordinator. The coordinator can run on nodes with [coordinatorRole]; every node starts a singleton proxy and exposes
 * [PekkoEntityWaker] through the service registry for GM/control code.
 */
class PekkoEntityWakerModuleBuilder {
    var moduleName: String = "pekko-entity-waker"
    var singletonName: String = "asteriaEntityWaker"
    var coordinatorRole: RoleKey? = null
    var reconcileOnStart: Boolean = true
    var reconcileOnConfigReload: Boolean = true
    private val tasks: MutableList<PekkoEntityWakeTask<*>> = mutableListOf()

    /**
     * Restricts the coordinator singleton host to nodes with [value].
     */
    fun coordinatorRole(value: String) {
        coordinatorRole = RoleKey(value)
    }

    /**
     * Adds one wake task.
     */
    fun <ID : Any> task(
        name: String,
        configure: PekkoEntityWakeTaskBuilder<ID>.() -> Unit,
    ) {
        tasks += PekkoEntityWakeTaskBuilder<ID>(name).apply(configure).build()
    }

    internal fun build(): PekkoEntityWakerOptions {
        return PekkoEntityWakerOptions(
            moduleName = moduleName,
            singletonName = singletonName,
            coordinatorRole = coordinatorRole,
            reconcileOnStart = reconcileOnStart,
            reconcileOnConfigReload = reconcileOnConfigReload,
            tasks = tasks.toList(),
        )
    }
}

/**
 * Runtime control service for GM and operational tools.
 */
class PekkoEntityWaker(
    private val proxy: ActorRef,
) {
    /**
     * Reloads all task sources and queues newly desired targets.
     */
    fun reconcile() {
        proxy.tell(PekkoEntityWakerCommand.Reconcile, ActorRef.noSender())
    }

    /**
     * Manually queues targets and clears a previous [cancel] for them.
     *
     * Control messages may cross nodes through the singleton proxy, so target ids must be `String`, `Long`, or `Int`
     * and must use the same type as the task source ids.
     */
    fun wake(
        taskName: String,
        targetIds: Iterable<Serializable>,
    ) {
        proxy.tell(PekkoEntityWakerCommand.WakeTargets(taskName, targetIds.toList()), ActorRef.noSender())
    }

    /**
     * Suppresses targets from automatic reconcile/retry.
     *
     * Already in-flight asks cannot be interrupted, but their result will not schedule more retries after cancellation.
     * Target ids follow the same wire contract as [wake].
     */
    fun cancel(
        taskName: String,
        targetIds: Iterable<Serializable>,
    ) {
        proxy.tell(PekkoEntityWakerCommand.CancelTargets(taskName, targetIds.toList()), ActorRef.noSender())
    }

    /**
     * Returns a bounded status snapshot for all tasks or one task.
     */
    suspend fun status(
        taskName: String? = null,
        targetLimit: Int = 100,
        timeout: Duration = 3.seconds,
    ): PekkoEntityWakerStatus {
        val response = Patterns.ask(
            proxy,
            PekkoEntityWakerCommand.GetStatus(taskName, targetLimit),
            timeout.toJavaDuration(),
        ).await()
        return response as? PekkoEntityWakerStatus
            ?: error("expected PekkoEntityWakerStatus, got ${response::class.qualifiedName}")
    }
}

sealed interface PekkoEntityWakerCommand : Serializable {
    data object Reconcile : PekkoEntityWakerCommand {
        private fun readResolve(): Any = Reconcile
    }

    data class WakeTargets(
        val taskName: String,
        val targetIds: List<Serializable>,
    ) : PekkoEntityWakerCommand {
        init {
            require(taskName.isNotBlank()) { "wake task name must not be blank" }
            require(targetIds.isNotEmpty()) { "wake target ids must not be empty" }
            targetIds.requireSupportedWakeTargetIds()
        }
    }

    data class CancelTargets(
        val taskName: String,
        val targetIds: List<Serializable>,
    ) : PekkoEntityWakerCommand {
        init {
            require(taskName.isNotBlank()) { "wake task name must not be blank" }
            require(targetIds.isNotEmpty()) { "wake target ids must not be empty" }
            targetIds.requireSupportedWakeTargetIds()
        }
    }

    data class GetStatus(
        val taskName: String? = null,
        val targetLimit: Int = 100,
    ) : PekkoEntityWakerCommand {
        init {
            taskName?.let { require(it.isNotBlank()) { "wake task name must not be blank" } }
            require(targetLimit >= 0) { "wake status target limit must not be negative" }
        }
    }
}

data class PekkoEntityWakerStatus(
    val tasks: List<PekkoEntityWakeTaskStatus>,
) : Serializable

data class PekkoEntityWakeTaskStatus(
    val name: String,
    val entityKind: String,
    val desired: Int,
    val pending: Int,
    val inFlight: Int,
    val retrying: Int,
    val completed: Int,
    val cancelled: Int,
    val failed: Int,
    val exhausted: Int,
    val currentConcurrency: Int,
    val targets: PekkoEntityWakeTargetStatusSamples,
) : Serializable

data class PekkoEntityWakeTargetStatusSamples(
    val pending: List<String>,
    val inFlight: List<String>,
    val retrying: List<String>,
    val completed: List<String>,
    val cancelled: List<String>,
    val failed: List<PekkoEntityWakeFailureStatus>,
    val exhausted: List<PekkoEntityWakeFailureStatus>,
) : Serializable

data class PekkoEntityWakeFailureStatus(
    val targetId: String,
    val attempts: Int,
    val message: String?,
) : Serializable

class PekkoEntityWakerModule(
    private val options: PekkoEntityWakerOptions,
) : AsteriaModule {
    override val name: String = options.moduleName
    private var configReloadSubscription: ConfigReloadSubscription? = null
    private var proxy: ActorRef? = null

    override suspend fun start(context: ModuleContext) {
        val system = context.services.get<ActorSystem>()
        startCoordinatorHostIfNeeded(context, system)
        val proxyRef = startCoordinatorProxy(system)
        proxy = proxyRef
        context.services.register(PekkoEntityWaker::class, PekkoEntityWaker(proxyRef))
        if (options.reconcileOnConfigReload) {
            configReloadSubscription = context.services.find<ConfigService>()?.subscribe {
                proxyRef.tell(PekkoEntityWakerCommand.Reconcile, ActorRef.noSender())
            }
        }
        if (options.reconcileOnStart) {
            proxyRef.tell(PekkoEntityWakerCommand.Reconcile, ActorRef.noSender())
        }
    }

    override suspend fun stop(context: ModuleContext) {
        configReloadSubscription?.close()
        configReloadSubscription = null
        proxy = null
    }

    private fun startCoordinatorHostIfNeeded(
        context: ModuleContext,
        system: ActorSystem,
    ) {
        val role = options.coordinatorRole
        if (role != null && role !in context.roles) {
            return
        }
        val settings = ClusterSingletonManagerSettings.create(system).withOptionalRole(role)
        system.actorOf(
            ClusterSingletonManager.props(
                PekkoEntityWakerCoordinatorActor.props(
                    runtime = context.runtime,
                    tasks = options.tasks.map { it.erased() },
                ),
                PekkoEntityWakerCoordinatorActor.STOP,
                settings,
            ),
            options.singletonName,
        )
    }

    private fun startCoordinatorProxy(system: ActorSystem): ActorRef {
        val settings = ClusterSingletonProxySettings.create(system).withOptionalRole(options.coordinatorRole)
        return system.actorOf(
            ClusterSingletonProxy.props("/user/${options.singletonName}", settings),
            "${options.singletonName}Proxy",
        )
    }

    companion object {
        operator fun invoke(configure: PekkoEntityWakerModuleBuilder.() -> Unit): PekkoEntityWakerModule {
            return PekkoEntityWakerModule(PekkoEntityWakerModuleBuilder().apply(configure).build())
        }
    }
}

private fun ClusterSingletonManagerSettings.withOptionalRole(role: RoleKey?): ClusterSingletonManagerSettings {
    return role?.let { withRole(it.value) } ?: this
}

private fun ClusterSingletonProxySettings.withOptionalRole(role: RoleKey?): ClusterSingletonProxySettings {
    return role?.let { withRole(it.value) } ?: this
}

private fun Iterable<Serializable>.requireSupportedWakeTargetIds() {
    forEach { targetId ->
        require(targetId is String || targetId is Long || targetId is Int) {
            "wake target id type ${targetId::class.qualifiedName} is not supported across Pekko serialization; " +
                    "use String, Long, or Int"
        }
    }
}
