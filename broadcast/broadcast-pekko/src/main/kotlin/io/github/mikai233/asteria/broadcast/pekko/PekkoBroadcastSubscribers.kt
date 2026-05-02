package io.github.mikai233.asteria.broadcast.pekko

import io.github.mikai233.asteria.broadcast.BroadcastEnvelope
import io.github.mikai233.asteria.broadcast.BroadcastSubscriber
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
