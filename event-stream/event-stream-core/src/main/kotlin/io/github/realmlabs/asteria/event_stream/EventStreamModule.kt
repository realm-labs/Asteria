package io.github.realmlabs.asteria.event_stream

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext

/**
 * Installs an in-memory durable event bus for tests and local development.
 *
 * The in-memory implementation records events only inside the current process and does not provide external
 * persistence, replay, or cross-process delivery.
 */
class InMemoryDurableEventModule(
    private val busFactory: (ModuleContext) -> InMemoryDurableEventBus = { InMemoryDurableEventBus() },
) : AsteriaModule {
    constructor(bus: InMemoryDurableEventBus) : this({ bus })

    override val name: String = "event-stream-in-memory"

    override suspend fun install(context: ModuleContext) {
        val bus = busFactory(context)
        context.services.register(InMemoryDurableEventBus::class, bus)
        context.services.register(DurableEventBus::class, bus)
        context.services.register(DurableEventPublisher::class, bus)
        context.services.register(DurableEventConsumer::class, bus)
    }
}
