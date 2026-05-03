package io.github.realmlabs.asteria.broadcast

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.observability.metricsOrNoop

/**
 * Installs a local [BroadcastBus].
 *
 * Applications that need cluster-wide broadcast should install a runtime-specific
 * module, such as the Pekko broadcast module, instead of this local module.
 */
class LocalBroadcastModule(
    private val busFactory: (ModuleContext) -> BroadcastBus = { context -> LocalBroadcastBus(context.metricsOrNoop()) },
) : AsteriaModule {
    constructor(bus: BroadcastBus) : this({ bus })

    override val name: String = "broadcast-local"

    override suspend fun install(context: ModuleContext) {
        context.services.register(BroadcastBus::class, busFactory(context))
    }
}
