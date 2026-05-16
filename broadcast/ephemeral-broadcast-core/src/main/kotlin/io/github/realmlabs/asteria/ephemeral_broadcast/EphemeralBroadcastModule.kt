package io.github.realmlabs.asteria.ephemeral_broadcast

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.observability.metricsOrNoop

/**
 * Installs a local [EphemeralBroadcastBus].
 *
 * Applications that need cluster-wide ephemeral broadcast should install a runtime-specific
 * module, such as the Pekko ephemeral broadcast module, instead of this local module.
 */
class LocalEphemeralBroadcastModule(
    private val busFactory: (ModuleContext) -> EphemeralBroadcastBus = { context -> LocalEphemeralBroadcastBus(context.metricsOrNoop()) },
) : AsteriaModule {
    constructor(bus: EphemeralBroadcastBus) : this({ bus })

    override val name: String = "ephemeral-broadcast-local"

    override suspend fun install(context: ModuleContext) {
        context.services.register(EphemeralBroadcastBus::class, busFactory(context))
    }
}
