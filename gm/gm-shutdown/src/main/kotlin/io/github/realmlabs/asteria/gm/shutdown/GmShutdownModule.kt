package io.github.realmlabs.asteria.gm.shutdown

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import java.time.Clock

class GmShutdownModule(
    private val plan: GmShutdownPlan,
    private val clock: Clock = Clock.systemUTC(),
) : AsteriaModule {
    override val name: String = "gm-shutdown"

    override suspend fun install(context: ModuleContext) {
        val coordinator = GmShutdownCoordinator(
            plan = plan,
            services = context.services,
            runtime = context.runtime,
            clock = clock,
        )
        context.services.register(GmShutdownOperations::class, coordinator)
        context.services.register(GmShutdownCoordinator::class, coordinator)
    }
}

fun gmShutdownModule(
    name: String = "game-shutdown",
    clock: Clock = Clock.systemUTC(),
    configure: GmShutdownPlanBuilder.() -> Unit,
): GmShutdownModule {
    return GmShutdownModule(gmShutdownPlan(name, configure), clock)
}
