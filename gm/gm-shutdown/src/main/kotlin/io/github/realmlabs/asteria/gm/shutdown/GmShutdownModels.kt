package io.github.realmlabs.asteria.gm.shutdown

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.ServiceRegistry
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/**
 * Request submitted by GM or an operations workflow to run one shutdown plan.
 */
data class GmShutdownRequest(
    val runId: GmShutdownRunId = GmShutdownRunId(UUID.randomUUID().toString()),
    val operator: String,
    val reason: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(operator.isNotBlank()) { "shutdown operator must not be blank" }
        require(reason.isNotBlank()) { "shutdown reason must not be blank" }
        metadata.keys.forEach { require(it.isNotBlank()) { "shutdown metadata key must not be blank" } }
    }
}

@JvmInline
value class GmShutdownRunId(val value: String) {
    init {
        require(value.isNotBlank()) { "shutdown run id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Immutable shutdown plan assembled by business code.
 */
data class GmShutdownPlan(
    val name: String,
    val phases: List<GmShutdownPhase>,
) {
    init {
        require(name.isNotBlank()) { "shutdown plan name must not be blank" }
        require(phases.isNotEmpty()) { "shutdown plan must contain at least one phase" }
        require(phases.map { it.name }.toSet().size == phases.size) { "shutdown phase names must be unique" }
    }
}

data class GmShutdownPhase(
    val name: String,
    val steps: List<GmShutdownStep>,
    val timeout: Duration? = null,
) {
    init {
        require(name.isNotBlank()) { "shutdown phase name must not be blank" }
        require(steps.isNotEmpty()) { "shutdown phase $name must contain at least one step" }
        require(steps.map { it.name }.toSet().size == steps.size) {
            "shutdown step names in phase $name must be unique"
        }
    }
}

/**
 * One executable business shutdown action, such as draining gateway sessions or stopping world actors.
 */
fun interface GmShutdownAction {
    suspend fun run(context: GmShutdownContext): GmShutdownStepResult
}

interface GmShutdownStep {
    val name: String
    val timeout: Duration?
    val continueOnFailure: Boolean

    suspend fun execute(context: GmShutdownContext): GmShutdownStepResult
}

data class DefaultGmShutdownStep(
    override val name: String,
    override val timeout: Duration? = null,
    override val continueOnFailure: Boolean = false,
    private val action: GmShutdownAction,
) : GmShutdownStep {
    init {
        require(name.isNotBlank()) { "shutdown step name must not be blank" }
    }

    override suspend fun execute(context: GmShutdownContext): GmShutdownStepResult {
        return action.run(context)
    }
}

data class GmShutdownContext(
    val request: GmShutdownRequest,
    val plan: GmShutdownPlan,
    val phase: GmShutdownPhase,
    val step: GmShutdownStep,
    val services: ServiceRegistry,
    val runtime: NodeRuntime? = null,
)

data class GmShutdownStepResult(
    val outcome: GmShutdownStepOutcome = GmShutdownStepOutcome.Succeeded,
    val message: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        message?.let { require(it.isNotBlank()) { "shutdown step result message must not be blank" } }
        details.keys.forEach { require(it.isNotBlank()) { "shutdown step result detail key must not be blank" } }
    }

    companion object {
        fun succeeded(message: String? = null, details: Map<String, String> = emptyMap()): GmShutdownStepResult {
            return GmShutdownStepResult(GmShutdownStepOutcome.Succeeded, message, details)
        }

        fun skipped(message: String, details: Map<String, String> = emptyMap()): GmShutdownStepResult {
            return GmShutdownStepResult(GmShutdownStepOutcome.Skipped, message, details)
        }

        fun failed(message: String, details: Map<String, String> = emptyMap()): GmShutdownStepResult {
            return GmShutdownStepResult(GmShutdownStepOutcome.Failed, message, details)
        }
    }
}

enum class GmShutdownStepOutcome {
    Succeeded,
    Skipped,
    Failed,
}

enum class GmShutdownRunState {
    Idle,
    Running,
    Succeeded,
    Failed,
}

enum class GmShutdownPhaseState {
    Pending,
    Running,
    Succeeded,
    Failed,
    Skipped,
}

enum class GmShutdownStepState {
    Pending,
    Running,
    Succeeded,
    Skipped,
    Failed,
}

data class GmShutdownRunStatus(
    val planName: String,
    val state: GmShutdownRunState = GmShutdownRunState.Idle,
    val request: GmShutdownRequest? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val currentPhase: String? = null,
    val currentStep: String? = null,
    val phases: List<GmShutdownPhaseStatus> = emptyList(),
    val error: String? = null,
)

data class GmShutdownPhaseStatus(
    val name: String,
    val state: GmShutdownPhaseState = GmShutdownPhaseState.Pending,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val steps: List<GmShutdownStepStatus>,
    val error: String? = null,
)

data class GmShutdownStepStatus(
    val name: String,
    val state: GmShutdownStepState = GmShutdownStepState.Pending,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val result: GmShutdownStepResult? = null,
    val error: String? = null,
)
