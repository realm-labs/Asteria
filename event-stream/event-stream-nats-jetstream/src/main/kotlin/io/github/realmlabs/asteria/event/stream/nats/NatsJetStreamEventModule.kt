package io.github.realmlabs.asteria.event.stream.nats

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.event.stream.DurableEventBus
import io.github.realmlabs.asteria.event.stream.DurableEventConsumer
import io.github.realmlabs.asteria.event.stream.DurableEventPublisher
import io.nats.client.Connection

/**
 * Installs [NatsJetStreamEventBus].
 */
class NatsJetStreamEventModule(
    private val connectionFactory: (ModuleContext) -> Connection,
    private val busOptions: NatsJetStreamEventBusOptions = NatsJetStreamEventBusOptions(),
) : AsteriaModule {
    constructor(
        connection: Connection,
        busOptions: NatsJetStreamEventBusOptions = NatsJetStreamEventBusOptions(),
    ) : this({ connection }, busOptions)

    override val name: String = "event-stream-nats-jetstream"

    private var bus: NatsJetStreamEventBus? = null

    override suspend fun install(context: ModuleContext) {
        val eventBus = NatsJetStreamEventBus(connectionFactory(context), busOptions)
        bus = eventBus
        context.services.register(NatsJetStreamEventBus::class, eventBus)
        context.services.register(DurableEventBus::class, eventBus)
        context.services.register(DurableEventPublisher::class, eventBus)
        context.services.register(DurableEventConsumer::class, eventBus)
    }

    override suspend fun uninstall(context: ModuleContext) {
        bus?.close()
        bus = null
    }
}
