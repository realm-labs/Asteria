package io.github.realmlabs.asteria.actor

import kotlinx.coroutines.withTimeout
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorRef
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Composable lifecycle state machine for [AsteriaActor].
 *
 * This is intentionally a component instead of an actor base class, so it can be used together with other actor
 * features such as script support. Business actors still own their normal `Receive` definitions and can keep using
 * Pekko's `context.become` model.
 *
 * Typical usage:
 *
 * ```kotlin
 * private val gate = ActorLifecycleGate(
 *     owner = this,
 *     load = { data.start() },
 *     drain = { data.drain() },
 * )
 *
 * override fun preStart() {
 *     super.preStart()
 *     gate.startLoading()
 * }
 *
 * override fun createReceive(): Receive = gate.loadingReceive(::runningReceive)
 *
 * private fun runningReceive(): Receive = receiveBuilder()
 *     .match(ActorGracefulStop::class.java) { gate.beginStop(sender) }
 *     .match(PlayerCommand::class.java) { handle(it) }
 *     .build()
 * ```
 */
class ActorLifecycleGate(
    private val owner: AsteriaActor<*>,
    private val load: suspend () -> Unit,
    private val drain: suspend () -> Boolean = { true },
    private val options: ActorLifecycleGateOptions = ActorLifecycleGateOptions(),
) {
    private var state: ActorLifecycleState = ActorLifecycleState.New
    private var stashedDuringLoad: Int = 0

    fun startLoading() {
        check(state == ActorLifecycleState.New) { "actor lifecycle gate already started: $state" }
        state = ActorLifecycleState.Loading
        owner.launch(timeout = null) {
            runCatching {
                withTimeout(options.loadTimeout) {
                    load()
                }
            }.fold(
                onSuccess = { owner.self.tell(ActorLifecycleLoaded, owner.self) },
                onFailure = { owner.self.tell(ActorLifecycleLoadFailed(it), owner.self) },
            )
        }
    }

    fun loadingReceive(running: () -> AbstractActor.Receive): AbstractActor.Receive {
        return owner.receiveBuilder()
            .match(ActorLifecycleLoaded::class.java) {
                state = ActorLifecycleState.Running
                owner.context.become(running())
                owner.unstashAll()
            }
            .match(ActorLifecycleLoadFailed::class.java) {
                state = ActorLifecycleState.Failed
                options.onLoadFailed(owner, it.cause)
            }
            .matchAny {
                stashLoadingMessage(it)
            }
            .build()
    }

    fun beginStop(replyTo: ActorRef = owner.sender): Boolean {
        if (state != ActorLifecycleState.Running) {
            return false
        }
        state = ActorLifecycleState.Stopping
        owner.context.become(stoppingReceive())
        owner.launch(timeout = null) {
            runCatching {
                withTimeout(options.stopTimeout) {
                    check(drain()) { "actor drain returned false" }
                }
            }.fold(
                onSuccess = { owner.self.tell(ActorLifecycleDrained(replyTo), owner.self) },
                onFailure = { owner.self.tell(ActorLifecycleDrainFailed(it, replyTo), owner.self) },
            )
        }
        return true
    }

    fun stoppingReceive(): AbstractActor.Receive {
        return owner.receiveBuilder()
            .match(ActorLifecycleDrained::class.java) {
                state = ActorLifecycleState.Stopped
                it.replyTo.tellIfPresent(ActorGracefulStopSucceeded, owner.self)
                owner.context.stop(owner.self)
            }
            .match(ActorLifecycleDrainFailed::class.java) {
                state = ActorLifecycleState.Failed
                it.replyTo.tellIfPresent(ActorGracefulStopFailed(it.cause), owner.self)
                options.onDrainFailed(owner, it.cause)
            }
            .matchAny {
                when (options.stoppingMessageStrategy) {
                    StoppingMessageStrategy.Drop -> Unit
                    StoppingMessageStrategy.Stash -> owner.stash()
                }
            }
            .build()
    }

    private fun stashLoadingMessage(message: Any) {
        stashedDuringLoad += 1
        check(stashedDuringLoad <= options.maxLoadingStashSize) {
            "actor ${owner.self.path()} exceeded loading stash limit ${options.maxLoadingStashSize} on ${message.javaClass.name}"
        }
        owner.stash()
    }

    private fun ActorRef.tellIfPresent(message: Any, sender: ActorRef) {
        if (this != ActorRef.noSender()) {
            tell(message, sender)
        }
    }
}

data class ActorLifecycleGateOptions(
    val loadTimeout: Duration = 30.seconds,
    val stopTimeout: Duration = 30.seconds,
    val maxLoadingStashSize: Int = 4_096,
    val stoppingMessageStrategy: StoppingMessageStrategy = StoppingMessageStrategy.Drop,
    val onLoadFailed: (AsteriaActor<*>, Throwable) -> Unit = { actor, error ->
        actor.logger.error(error, "actor {} failed to load", actor.self)
        actor.context.stop(actor.self)
    },
    val onDrainFailed: (AsteriaActor<*>, Throwable) -> Unit = { actor, error ->
        actor.logger.error(error, "actor {} failed to drain before stop", actor.self)
        actor.context.stop(actor.self)
    },
)

enum class StoppingMessageStrategy {
    Drop,
    Stash,
}

data object ActorGracefulStop

data object ActorGracefulStopSucceeded

data class ActorGracefulStopFailed(
    val cause: Throwable,
)

private enum class ActorLifecycleState {
    New,
    Loading,
    Running,
    Stopping,
    Stopped,
    Failed,
}

private data object ActorLifecycleLoaded

private data class ActorLifecycleLoadFailed(
    val cause: Throwable,
)

private data class ActorLifecycleDrained(
    val replyTo: ActorRef,
)

private data class ActorLifecycleDrainFailed(
    val cause: Throwable,
    val replyTo: ActorRef,
)
