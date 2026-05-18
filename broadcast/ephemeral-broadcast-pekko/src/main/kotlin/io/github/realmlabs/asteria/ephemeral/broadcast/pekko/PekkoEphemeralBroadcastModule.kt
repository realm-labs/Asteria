package io.github.realmlabs.asteria.ephemeral.broadcast.pekko

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.ephemeral.broadcast.EphemeralBroadcastBus
import io.github.realmlabs.asteria.observability.metricsOrNoop
import org.apache.pekko.actor.ActorSystem

/**
 * Installs [PekkoEphemeralBroadcastBus].
 *
 * Install this module after the Pekko runtime module so [ActorSystem] is already
 * available in the service registry.
 */
class PekkoEphemeralBroadcastModule : AsteriaModule {
    override val name: String = "ephemeral-broadcast-pekko"

    private var bus: PekkoEphemeralBroadcastBus? = null

    override suspend fun install(context: ModuleContext) {
        val system = context.services.get<ActorSystem>()
        val pekkoBus = PekkoEphemeralBroadcastBus(system, metrics = context.metricsOrNoop())
        bus = pekkoBus
        context.services.register(PekkoEphemeralBroadcastBus::class, pekkoBus)
        context.services.register(EphemeralBroadcastBus::class, pekkoBus)
    }

    override suspend fun stop(context: ModuleContext) {
        bus?.close()
        bus = null
    }
}
