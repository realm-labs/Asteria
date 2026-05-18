package io.github.realmlabs.asteria.ephemeral.broadcast.pekko

import io.github.realmlabs.asteria.ephemeral.broadcast.EphemeralBroadcastEnvelope
import io.github.realmlabs.asteria.ephemeral.broadcast.EphemeralBroadcastSubscriber
import org.apache.pekko.actor.ActorRef

/**
 * [EphemeralBroadcastSubscriber] that forwards each envelope to a Pekko actor.
 */
class ActorRefEphemeralBroadcastSubscriber(
    private val ref: ActorRef,
    private val mapEnvelope: (EphemeralBroadcastEnvelope) -> Any = { it },
) : EphemeralBroadcastSubscriber {
    override fun onBroadcast(envelope: EphemeralBroadcastEnvelope) {
        ref.tell(mapEnvelope(envelope), ActorRef.noSender())
    }
}
