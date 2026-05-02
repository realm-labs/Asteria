package io.github.mikai233.asteria.broadcast.pekko

import io.github.mikai233.asteria.broadcast.BroadcastBus
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import io.github.mikai233.asteria.observability.metricsOrNoop
import org.apache.pekko.actor.ActorSystem

/**
 * Installs [PekkoBroadcastBus].
 *
 * Install this module after the Pekko runtime module so [ActorSystem] is already
 * available in the service registry.
 */
class PekkoBroadcastModule : AsteriaModule {
    override val name: String = "broadcast-pekko"

    private var bus: PekkoBroadcastBus? = null

    override suspend fun install(context: ModuleContext) {
        val system = context.services.get<ActorSystem>()
        val pekkoBus = PekkoBroadcastBus(system, metrics = context.metricsOrNoop())
        bus = pekkoBus
        context.services.register(PekkoBroadcastBus::class, pekkoBus)
        context.services.register(BroadcastBus::class, pekkoBus)
    }

    override suspend fun stop(context: ModuleContext) {
        bus?.close()
        bus = null
    }
}
