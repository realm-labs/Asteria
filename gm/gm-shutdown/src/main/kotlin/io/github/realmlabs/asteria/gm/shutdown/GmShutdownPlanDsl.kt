package io.github.realmlabs.asteria.gm.shutdown

import io.github.realmlabs.asteria.core.AsteriaDsl
import kotlin.time.Duration

fun gmShutdownPlan(
    name: String = "game-shutdown",
    configure: GmShutdownPlanBuilder.() -> Unit,
): GmShutdownPlan {
    return GmShutdownPlanBuilder(name).apply(configure).build()
}

@AsteriaDsl
class GmShutdownPlanBuilder internal constructor(
    private val name: String,
) {
    private val phases: MutableList<GmShutdownPhase> = mutableListOf()

    fun phase(
        name: String,
        timeout: Duration? = null,
        configure: GmShutdownPhaseBuilder.() -> Unit,
    ) {
        phases += GmShutdownPhaseBuilder(name, timeout).apply(configure).build()
    }

    internal fun build(): GmShutdownPlan {
        return GmShutdownPlan(name, phases.toList())
    }
}

@AsteriaDsl
class GmShutdownPhaseBuilder internal constructor(
    private val name: String,
    private val timeout: Duration?,
) {
    private val steps: MutableList<GmShutdownStep> = mutableListOf()

    fun step(
        name: String,
        timeout: Duration? = null,
        continueOnFailure: Boolean = false,
        action: GmShutdownAction,
    ) {
        steps += DefaultGmShutdownStep(
            name = name,
            timeout = timeout,
            continueOnFailure = continueOnFailure,
            action = action,
        )
    }

    fun step(step: GmShutdownStep) {
        steps += step
    }

    internal fun build(): GmShutdownPhase {
        return GmShutdownPhase(name, steps.toList(), timeout)
    }
}
