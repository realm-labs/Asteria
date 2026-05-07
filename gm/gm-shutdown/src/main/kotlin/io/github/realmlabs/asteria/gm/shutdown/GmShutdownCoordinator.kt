package io.github.realmlabs.asteria.gm.shutdown

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.ServiceRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.Instant

interface GmShutdownOperations {
    val plan: GmShutdownPlan

    fun status(): GmShutdownRunStatus

    suspend fun run(request: GmShutdownRequest): GmShutdownRunStatus
}

/**
 * Executes a business-defined graceful shutdown plan and keeps the latest in-memory run status for GM inspection.
 *
 * The coordinator intentionally does not know about player, world, gateway, or process-exit semantics. Business modules
 * assemble those as ordered [GmShutdownStep] instances.
 */
class GmShutdownCoordinator(
    override val plan: GmShutdownPlan,
    private val services: ServiceRegistry,
    private val runtime: NodeRuntime? = null,
    private val clock: Clock = Clock.systemUTC(),
) : GmShutdownOperations {
    private val runLock = Mutex()
    private val statusLock = Any()

    @Volatile
    private var currentStatus: GmShutdownRunStatus = idleStatus()

    override fun status(): GmShutdownRunStatus = currentStatus

    override suspend fun run(request: GmShutdownRequest): GmShutdownRunStatus {
        check(runLock.tryLock()) { "shutdown plan ${plan.name} is already running" }
        try {
            publish(
                idleStatus().copy(
                    state = GmShutdownRunState.Running,
                    request = request,
                    startedAt = now(),
                ),
            )

            val completed = runCatching {
                plan.phases.forEach { phase -> runPhase(request, phase) }
                updateStatus {
                    it.copy(
                        state = GmShutdownRunState.Succeeded,
                        completedAt = now(),
                        currentPhase = null,
                        currentStep = null,
                    )
                }
            }.getOrElse { error ->
                updateStatus {
                    it.copy(
                        state = GmShutdownRunState.Failed,
                        completedAt = now(),
                        currentPhase = null,
                        currentStep = null,
                        error = error.shutdownMessage(),
                    )
                }
            }
            return completed
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun runPhase(
        request: GmShutdownRequest,
        phase: GmShutdownPhase,
    ) {
        updatePhase(phase.name) {
            it.copy(
                state = GmShutdownPhaseState.Running,
                startedAt = now(),
                completedAt = null,
                error = null,
            )
        }
        updateStatus { it.copy(currentPhase = phase.name, currentStep = null) }

        val result = runCatching {
            if (phase.timeout == null) {
                executePhase(request, phase)
            } else {
                withTimeout(phase.timeout) {
                    executePhase(request, phase)
                }
            }
        }

        result.fold(
            onSuccess = {
                updatePhase(phase.name) {
                    it.copy(
                        state = GmShutdownPhaseState.Succeeded,
                        completedAt = now(),
                    )
                }
            },
            onFailure = { error ->
                markRunningStepFailed(error)
                updatePhase(phase.name) {
                    it.copy(
                        state = GmShutdownPhaseState.Failed,
                        completedAt = now(),
                        error = error.shutdownMessage(),
                    )
                }
                throw GmShutdownExecutionFailed("shutdown phase ${phase.name} failed: ${error.shutdownMessage()}")
            },
        )
    }

    private suspend fun executePhase(
        request: GmShutdownRequest,
        phase: GmShutdownPhase,
    ) {
        phase.steps.forEach { step ->
            val fatalError = runStep(request, phase, step)
            if (fatalError != null) {
                throw fatalError
            }
        }
    }

    private suspend fun runStep(
        request: GmShutdownRequest,
        phase: GmShutdownPhase,
        step: GmShutdownStep,
    ): Throwable? {
        updateStatus { it.copy(currentPhase = phase.name, currentStep = step.name) }
        updateStep(phase.name, step.name) {
            it.copy(
                state = GmShutdownStepState.Running,
                startedAt = now(),
                completedAt = null,
                result = null,
                error = null,
            )
        }

        val context = GmShutdownContext(
            request = request,
            plan = plan,
            phase = phase,
            step = step,
            services = services,
            runtime = runtime,
        )
        val stepTimeout = step.timeout
        val result = runCatching {
            if (stepTimeout == null) {
                step.execute(context)
            } else {
                withTimeout(stepTimeout) {
                    step.execute(context)
                }
            }
        }

        return result.fold(
            onSuccess = { stepResult -> handleStepResult(phase, step, stepResult) },
            onFailure = { error -> handleStepError(phase, step, error) },
        )
    }

    private fun handleStepResult(
        phase: GmShutdownPhase,
        step: GmShutdownStep,
        result: GmShutdownStepResult,
    ): Throwable? {
        val state = when (result.outcome) {
            GmShutdownStepOutcome.Succeeded -> GmShutdownStepState.Succeeded
            GmShutdownStepOutcome.Skipped -> GmShutdownStepState.Skipped
            GmShutdownStepOutcome.Failed -> GmShutdownStepState.Failed
        }
        updateStep(phase.name, step.name) {
            it.copy(
                state = state,
                completedAt = now(),
                result = result,
                error = result.takeIf { it.outcome == GmShutdownStepOutcome.Failed }?.message,
            )
        }
        return if (result.outcome == GmShutdownStepOutcome.Failed && !step.continueOnFailure) {
            GmShutdownExecutionFailed("shutdown step ${step.name} failed: ${result.message ?: "failed"}")
        } else {
            null
        }
    }

    private fun handleStepError(
        phase: GmShutdownPhase,
        step: GmShutdownStep,
        error: Throwable,
    ): Throwable? {
        updateStep(phase.name, step.name) {
            it.copy(
                state = GmShutdownStepState.Failed,
                completedAt = now(),
                error = error.shutdownMessage(),
            )
        }
        return if (step.continueOnFailure) null else error
    }

    private fun markRunningStepFailed(error: Throwable) {
        val phase = currentStatus.currentPhase ?: return
        val step = currentStatus.currentStep ?: return
        updateStep(phase, step) { status ->
            if (status.state != GmShutdownStepState.Running) {
                status
            } else {
                status.copy(
                    state = GmShutdownStepState.Failed,
                    completedAt = now(),
                    error = error.shutdownMessage(),
                )
            }
        }
    }

    private fun updateStatus(transform: (GmShutdownRunStatus) -> GmShutdownRunStatus): GmShutdownRunStatus {
        return synchronized(statusLock) {
            transform(currentStatus).also { currentStatus = it }
        }
    }

    private fun publish(status: GmShutdownRunStatus) {
        synchronized(statusLock) {
            currentStatus = status
        }
    }

    private fun updatePhase(
        phaseName: String,
        transform: (GmShutdownPhaseStatus) -> GmShutdownPhaseStatus,
    ) {
        updateStatus { status ->
            status.copy(
                phases = status.phases.map { phase ->
                    if (phase.name == phaseName) transform(phase) else phase
                },
            )
        }
    }

    private fun updateStep(
        phaseName: String,
        stepName: String,
        transform: (GmShutdownStepStatus) -> GmShutdownStepStatus,
    ) {
        updatePhase(phaseName) { phase ->
            phase.copy(
                steps = phase.steps.map { step ->
                    if (step.name == stepName) transform(step) else step
                },
            )
        }
    }

    private fun idleStatus(): GmShutdownRunStatus {
        return GmShutdownRunStatus(
            planName = plan.name,
            phases = plan.phases.map { phase ->
                GmShutdownPhaseStatus(
                    name = phase.name,
                    steps = phase.steps.map { step -> GmShutdownStepStatus(name = step.name) },
                )
            },
        )
    }

    private fun now(): Instant = clock.instant()
}

private class GmShutdownExecutionFailed(message: String) : RuntimeException(message)

private fun Throwable.shutdownMessage(): String {
    return when (this) {
        is TimeoutCancellationException -> "timed out"
        else -> message ?: javaClass.name
    }
}
