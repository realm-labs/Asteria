package io.github.realmlabs.asteria.broadcast.pekko

import io.github.realmlabs.asteria.broadcast.BroadcastEnvelope
import io.github.realmlabs.asteria.broadcast.BroadcastSubscriber
import org.apache.pekko.actor.ActorRef

/**
 * [BroadcastSubscriber] that forwards each envelope to a Pekko actor.
 */
class ActorRefBroadcastSubscriber(
    private val ref: ActorRef,
    private val mapEnvelope: (BroadcastEnvelope) -> Any = { it },
) : BroadcastSubscriber {
    override fun onBroadcast(envelope: BroadcastEnvelope) {
        ref.tell(mapEnvelope(envelope), ActorRef.noSender())
    }
}
