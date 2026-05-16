package io.github.realmlabs.asteria.cluster.pekko

import io.github.realmlabs.asteria.actor.AsteriaActor
import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.RoleKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.ClusterEvent.*
import org.apache.pekko.cluster.Member
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.pattern.Patterns
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.toJavaDuration

internal data class ErasedPekkoEntityWakeTask(
    val name: String,
    val entityKind: EntityKind,
    val targetSource: suspend (PekkoEntityWakeContext) -> Iterable<Any>,
    val targetIdDecoder: (PekkoEntityWakeTargetId) -> Any,
    val messageFactory: (Any) -> Any,
    val resultClassifier: PekkoEntityWakeResultClassifier,
    val concurrency: PekkoEntityWakeConcurrency,
    val retry: PekkoEntityWakeRetry,
    val readiness: PekkoEntityWakeReadiness,
)

internal class PekkoEntityWakerCoordinatorActor(
    runtime: NodeRuntime,
    tasks: List<ErasedPekkoEntityWakeTask>,
) : AsteriaActor<NodeRuntime>(runtime) {
    private val cluster: Cluster = Cluster.get(context.system)
    private val wakeContext = PekkoEntityWakeContext(runtime, runtime.services)
    private val states: Map<String, WakeTaskState> = tasks.associate { it.name to WakeTaskState(it) }

    override fun preStart() {
        cluster.subscribe(self, ClusterEventInitialState, MemberEvent::class.java, ReachabilityEvent::class.java)
    }

    override fun postStop() {
        cluster.unsubscribe(self)
        super.postStop()
    }

    override fun createReceive(): Receive {
        return receiveBuilder()
            .matchEquals(PekkoEntityWakerCommand.Reconcile) { reconcileAll() }
            .match(PekkoEntityWakerCommand.WakeTargets::class.java) { handleManualWake(it) }
            .match(PekkoEntityWakerCommand.CancelTargets::class.java) { handleCancel(it) }
            .match(PekkoEntityWakerCommand.GetStatus::class.java) { handleStatus(it) }
            .match(CurrentClusterState::class.java) { handleClusterChanged() }
            .match(MemberEvent::class.java) { handleClusterChanged() }
            .match(ReachabilityEvent::class.java) { handleClusterChanged() }
            .match(TargetsLoaded::class.java) { handleTargetsLoaded(it) }
            .match(WakeCompleted::class.java) { handleWakeCompleted(it) }
            .match(RetryReady::class.java) { handleRetryReady(it) }
            .matchEquals(Pump) { pumpAll() }
            .matchEquals(STOP) { context.stop(self) }
            .build()
    }

    private fun reconcileAll() {
        states.values.forEach(::reconcile)
    }

    private fun handleStatus(command: PekkoEntityWakerCommand.GetStatus) {
        val selected = command.taskName
            ?.let { taskName -> listOfNotNull(states[taskName]) }
            ?: states.values.toList()
        sender.tell(PekkoEntityWakerStatus(selected.map { it.status(command.targetLimit) }), self)
    }

    private fun handleManualWake(command: PekkoEntityWakerCommand.WakeTargets) {
        val state = states[command.taskName] ?: return
        val targetIds = state.decodeTargetIds(command.targetIds) ?: return
        state.manualWake(targetIds)
        logger.info(
            "entity wake task {} manually queued targets={} pending={}",
            command.taskName,
            targetIds.size,
            state.pendingSize,
        )
        pump(state)
    }

    private fun handleCancel(command: PekkoEntityWakerCommand.CancelTargets) {
        val state = states[command.taskName] ?: return
        val targetIds = state.decodeTargetIds(command.targetIds) ?: return
        state.cancel(targetIds)
        logger.info(
            "entity wake task {} cancelled targets={} suppressed={}",
            command.taskName,
            targetIds.size,
            state.cancelled.size,
        )
    }

    private fun WakeTaskState.decodeTargetIds(targetIds: List<PekkoEntityWakeTargetId>): List<Any>? {
        return runCatching { targetIds.map(task.targetIdDecoder) }
            .onFailure { error ->
                val message = error.message ?: error::class.qualifiedName
                logger.warning("entity wake task ${task.name} rejected manual target ids: $message")
            }
            .getOrNull()
    }

    private fun reconcile(state: WakeTaskState) {
        if (!isReady(state.task.readiness)) {
            state.reconcileRequested = true
            logger.info("entity wake task {} waits for readiness", state.task.name)
            return
        }
        state.reconcileRequested = false
        coroutineScope.launchTracked {
            val loaded = runCatching { state.task.targetSource(wakeContext).toList() }
            self.tell(TargetsLoaded(state.task.name, loaded), self)
        }
    }

    private fun handleTargetsLoaded(message: TargetsLoaded) {
        val state = states[message.taskName] ?: return
        message.targets.onSuccess { targets ->
            state.mergeTargets(targets)
            logger.info(
                "entity wake task {} reconciled targets={} pending={} completed={}",
                state.task.name,
                targets.size,
                state.pendingSize,
                state.completed.size,
            )
            pump(state)
        }.onFailure { error ->
            logger.error(error, "entity wake task {} target source failed", state.task.name)
            scheduleReconcile(state.task.retry.initialDelay)
        }
    }

    private fun handleWakeCompleted(message: WakeCompleted) {
        val state = states[message.taskName] ?: return
        state.inFlight -= message.targetId
        val stillDesired = message.targetId in state.desired && message.targetId !in state.cancelled
        if (message.success && stillDesired) {
            state.completed += message.targetId
            state.attempts -= message.targetId
            state.clearFailure(message.targetId)
            state.record(success = true)
        } else {
            state.record(success = false)
            state.markFailed(message.targetId, message.error)
            if (stillDesired && shouldRetry(state, message.targetId)) {
                scheduleRetry(state, message.targetId)
            } else if (stillDesired) {
                scheduleExhaustedRetry(state, message.targetId)
            }
        }
        pump(state)
    }

    private fun handleRetryReady(message: RetryReady) {
        val state = states[message.taskName] ?: return
        if (message.resetAttempts) {
            state.attempts -= message.targetId
        }
        state.retrying -= message.targetId
        if (message.targetId in state.desired &&
            message.targetId !in state.completed &&
            message.targetId !in state.cancelled
        ) {
            state.enqueue(message.targetId)
        }
        pump(state)
    }

    private fun handleClusterChanged() {
        states.values.forEach { state ->
            if (state.reconcileRequested && isReady(state.task.readiness)) {
                reconcile(state)
            } else {
                pump(state)
            }
        }
    }

    private fun pumpAll() {
        states.values.forEach(::pump)
    }

    private fun pump(state: WakeTaskState) {
        if (!isReady(state.task.readiness)) {
            state.reconcileRequested = true
            return
        }
        val shard = runtime.services.find<EntityShardRegistry>()?.find(state.task.entityKind)
        if (shard == null) {
            logger.warning("entity wake task ${state.task.name} waits for shard ${state.task.entityKind}")
            schedulePump(state.task.retry.initialDelay)
            return
        }
        while (state.inFlight.size < state.currentConcurrency) {
            val targetId = state.poll() ?: return
            if (targetId !in state.desired ||
                targetId in state.completed ||
                targetId in state.inFlight ||
                targetId in state.cancelled
            ) {
                continue
            }
            state.inFlight += targetId
            val attempt = state.nextAttempt(targetId)
            val message = runCatching { state.task.messageFactory(targetId) }
            if (message.isFailure) {
                val error = message.exceptionOrNull()
                self.tell(
                    WakeCompleted(
                        state.task.name,
                        targetId,
                        success = false,
                        error = error?.message ?: error?.javaClass?.name,
                    ),
                    self,
                )
                logger.error(message.exceptionOrNull(), "entity wake task {} message factory failed", state.task.name)
                continue
            }
            coroutineScope.launchTracked {
                val result = askWake(state, shard, message.getOrThrow())
                self.tell(WakeCompleted(state.task.name, targetId, result.success, result.error), self)
            }
            logger.debug("entity wake task {} dispatched target={} attempt={}", state.task.name, targetId, attempt)
        }
    }

    private suspend fun askWake(
        state: WakeTaskState,
        shard: ActorRef,
        message: Any,
    ): WakeAttemptResult {
        return try {
            val response = Patterns.ask(shard, message, state.task.retry.timeout.toJavaDuration()).await()
            if (state.task.resultClassifier.successful(response)) {
                WakeAttemptResult(success = true)
            } else {
                WakeAttemptResult(success = false, error = "wake result rejected")
            }
        } catch (error: Throwable) {
            logger.warning("entity wake task ${state.task.name} wake failed: ${error.message}")
            WakeAttemptResult(success = false, error = error.message ?: error::class.qualifiedName)
        }
    }

    private fun shouldRetry(
        state: WakeTaskState,
        targetId: Any,
    ): Boolean {
        val maxAttempts = state.task.retry.maxAttempts ?: return true
        return state.attempts.getValue(targetId) < maxAttempts
    }

    private fun scheduleRetry(
        state: WakeTaskState,
        targetId: Any,
    ) {
        if (!state.retrying.add(targetId)) {
            return
        }
        val delay = state.retryDelay(targetId)
        coroutineScope.launchTracked {
            delay(delay)
            self.tell(RetryReady(state.task.name, targetId), self)
        }
    }

    private fun scheduleExhaustedRetry(
        state: WakeTaskState,
        targetId: Any,
    ) {
        val delay = state.task.retry.exhaustedDelay ?: return
        if (!state.retrying.add(targetId)) {
            return
        }
        logger.warning(
            "entity wake task ${state.task.name} target $targetId exhausted attempts; retrying after $delay",
        )
        state.markExhausted(targetId)
        coroutineScope.launchTracked {
            delay(delay)
            self.tell(RetryReady(state.task.name, targetId, resetAttempts = true), self)
        }
    }

    private fun scheduleReconcile(delay: Duration) {
        coroutineScope.launchTracked {
            delay(delay)
            self.tell(PekkoEntityWakerCommand.Reconcile, self)
        }
    }

    private fun schedulePump(delay: Duration) {
        coroutineScope.launchTracked {
            delay(delay)
            self.tell(Pump, self)
        }
    }

    private fun isReady(readiness: PekkoEntityWakeReadiness): Boolean {
        if (readiness.minUpRatio <= 0.0) {
            return true
        }
        val members = activeMembers(readiness.role)
        if (members.isEmpty()) {
            return false
        }
        val up = members.count { it.status() == MemberStatus.up() }
        return up.toDouble() / members.size >= readiness.minUpRatio
    }

    private fun activeMembers(role: RoleKey?): List<Member> {
        return cluster.state().members
            .filter { member -> member.status() != MemberStatus.removed() }
            .filter { member -> role == null || role.value in member.roles }
    }

    companion object {
        const val STOP: String = "stop-pekko-entity-waker"
        private val ClusterEventInitialState = initialStateAsEvents()

        fun props(
            runtime: NodeRuntime,
            tasks: List<ErasedPekkoEntityWakeTask>,
        ): Props {
            return Props.create(PekkoEntityWakerCoordinatorActor::class.java) {
                PekkoEntityWakerCoordinatorActor(runtime, tasks)
            }
        }
    }
}

private class WakeTaskState(
    val task: ErasedPekkoEntityWakeTask,
) {
    val desired: MutableSet<Any> = linkedSetOf()
    val completed: MutableSet<Any> = linkedSetOf()
    val cancelled: MutableSet<Any> = linkedSetOf()
    val inFlight: MutableSet<Any> = linkedSetOf()
    val retrying: MutableSet<Any> = linkedSetOf()
    val attempts: MutableMap<Any, Int> = linkedMapOf()
    private val failures: MutableMap<Any, WakeFailure> = linkedMapOf()
    private val pending: ArrayDeque<Any> = ArrayDeque()
    private val queued: MutableSet<Any> = linkedSetOf()
    private var windowTotal = 0
    private var windowSucceeded = 0
    private var cooldown = 0
    var reconcileRequested: Boolean = false
    var currentConcurrency: Int = task.concurrency.initial
        private set

    val pendingSize: Int get() = pending.size

    fun mergeTargets(targets: Collection<Any>) {
        desired.clear()
        desired.addAll(targets)
        pending.removeAll { it !in desired || it in cancelled }
        queued.removeAll { it !in desired || it in cancelled }
        retrying.removeAll { it !in desired || it in cancelled }
        failures.keys.removeAll { it !in desired || it in cancelled }
        desired.forEach { targetId ->
            if (targetId !in completed &&
                targetId !in inFlight &&
                targetId !in retrying &&
                targetId !in cancelled
            ) {
                enqueue(targetId)
            }
        }
    }

    fun manualWake(targetIds: Collection<Any>) {
        desired.addAll(targetIds)
        cancelled.removeAll(targetIds.toSet())
        completed.removeAll(targetIds.toSet())
        attempts.keys.removeAll(targetIds.toSet())
        failures.keys.removeAll(targetIds.toSet())
        targetIds.forEach { targetId ->
            if (targetId !in inFlight) {
                retrying -= targetId
                enqueue(targetId)
            }
        }
    }

    fun cancel(targetIds: Collection<Any>) {
        val ids = targetIds.toSet()
        cancelled.addAll(ids)
        pending.removeAll { it in ids }
        queued.removeAll(ids)
        retrying.removeAll(ids)
        attempts.keys.removeAll(ids)
        failures.keys.removeAll(ids)
    }

    fun enqueue(targetId: Any) {
        if (queued.add(targetId)) {
            pending.addLast(targetId)
        }
    }

    fun poll(): Any? {
        val targetId = pending.removeFirstOrNull() ?: return null
        queued -= targetId
        return targetId
    }

    fun nextAttempt(targetId: Any): Int {
        val attempt = attempts.getOrDefault(targetId, 0) + 1
        attempts[targetId] = attempt
        return attempt
    }

    fun markFailed(
        targetId: Any,
        message: String?,
    ) {
        failures[targetId] = WakeFailure(
            attempts = attempts.getOrDefault(targetId, 0),
            message = message,
            exhausted = false,
        )
    }

    fun markExhausted(targetId: Any) {
        val current = failures[targetId]
        failures[targetId] = WakeFailure(
            attempts = attempts.getOrDefault(targetId, current?.attempts ?: 0),
            message = current?.message,
            exhausted = true,
        )
    }

    fun clearFailure(targetId: Any) {
        failures -= targetId
    }

    fun retryDelay(targetId: Any): Duration {
        val attempt = attempts.getOrDefault(targetId, 1)
        val multiplier = task.retry.backoffFactor.pow((attempt - 1).coerceAtLeast(0))
        return (task.retry.initialDelay * multiplier).coerceAtMost(task.retry.maxDelay)
    }

    fun record(success: Boolean) {
        windowTotal += 1
        if (success) {
            windowSucceeded += 1
        }
        if (windowTotal >= task.concurrency.adjustmentWindow) {
            adjustConcurrency()
            windowTotal = 0
            windowSucceeded = 0
        }
    }

    private fun adjustConcurrency() {
        if (cooldown > 0) {
            cooldown -= 1
            return
        }
        val successRate = windowSucceeded.toDouble() / windowTotal
        val failureRate = 1.0 - successRate
        currentConcurrency = when {
            successRate >= task.concurrency.growthSuccessRate -> {
                cooldown = task.concurrency.cooldownWindows
                (currentConcurrency + task.concurrency.growthStep).coerceAtMost(task.concurrency.max)
            }

            failureRate >= task.concurrency.shrinkFailureRate -> {
                cooldown = task.concurrency.cooldownWindows
                (currentConcurrency - task.concurrency.shrinkStep).coerceAtLeast(task.concurrency.min)
            }

            else -> currentConcurrency
        }
    }

    fun status(targetLimit: Int): PekkoEntityWakeTaskStatus {
        return PekkoEntityWakeTaskStatus(
            name = task.name,
            entityKind = task.entityKind.value,
            desired = desired.size,
            pending = pending.size,
            inFlight = inFlight.size,
            retrying = retrying.size,
            completed = completed.size,
            cancelled = cancelled.size,
            failed = failures.count { !it.value.exhausted },
            exhausted = failures.count { it.value.exhausted },
            currentConcurrency = currentConcurrency,
            targets = PekkoEntityWakeTargetStatusSamples(
                pending = pending.sampleStrings(targetLimit),
                inFlight = inFlight.sampleStrings(targetLimit),
                retrying = retrying.sampleStrings(targetLimit),
                completed = completed.sampleStrings(targetLimit),
                cancelled = cancelled.sampleStrings(targetLimit),
                failed = failures.filter { !it.value.exhausted }.sampleFailures(targetLimit),
                exhausted = failures.filter { it.value.exhausted }.sampleFailures(targetLimit),
            ),
        )
    }
}

private data class WakeAttemptResult(
    val success: Boolean,
    val error: String? = null,
)

private data class WakeFailure(
    val attempts: Int,
    val message: String?,
    val exhausted: Boolean,
)

private data class TargetsLoaded(
    val taskName: String,
    val targets: Result<List<Any>>,
)

private data class WakeCompleted(
    val taskName: String,
    val targetId: Any,
    val success: Boolean,
    val error: String? = null,
)

private data class RetryReady(
    val taskName: String,
    val targetId: Any,
    val resetAttempts: Boolean = false,
)

private data object Pump {
    private fun readResolve(): Any = Pump
}

private fun Iterable<Any>.sampleStrings(limit: Int): List<String> {
    return take(limit).map { it.toString() }
}

private fun Map<Any, WakeFailure>.sampleFailures(limit: Int): List<PekkoEntityWakeFailureStatus> {
    return entries.take(limit).map { (targetId, failure) ->
        PekkoEntityWakeFailureStatus(
            targetId = targetId.toString(),
            attempts = failure.attempts,
            message = failure.message,
        )
    }
}
