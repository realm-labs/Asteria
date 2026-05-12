package io.github.realmlabs.asteria.actor

import kotlinx.coroutines.*
import org.apache.pekko.actor.ActorRef
import java.lang.Runnable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal data class ActorCoroutineTask(val runnable: Runnable) : Runnable by runnable

/**
 * Uses the actor mailbox as a coroutine dispatcher.
 *
 * The returned scope must be cancelled from actor shutdown, otherwise tracked jobs may keep references to actor state.
 */
fun ActorRef.actorCoroutineScope(): ActorCoroutineScope {
    val dispatcher = Executor { tell(ActorCoroutineTask(it), ActorRef.noSender()) }.asCoroutineDispatcher()
    return ActorCoroutineScope(dispatcher + SupervisorJob())
}

/**
 * Coroutine scope used by actor helpers to track and cancel actor-owned jobs.
 */
class ActorCoroutineScope(context: CoroutineContext) : CoroutineScope {
    private val rootJob = SupervisorJob(context[Job])
    override val coroutineContext: CoroutineContext = context.minusKey(Job) + rootJob

    private val jobs = ConcurrentHashMap.newKeySet<Job>()

    /**
     * Launches a job and keeps it in [activeJobs] until completion.
     */
    fun launchTracked(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val job = launch(context, start, block)
        track(job)
        return job
    }

    /**
     * Starts a deferred computation and keeps it in [activeJobs] until completion.
     */
    fun <T> asyncTracked(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T,
    ): Deferred<T> {
        val deferred = async(context, start, block)
        track(deferred)
        return deferred
    }

    fun activeJobs(): Set<Job> = jobs.toSet()

    fun cancel() {
        rootJob.cancel()
    }

    private fun track(job: Job) {
        jobs.add(job)
        job.invokeOnCompletion { jobs.remove(job) }
    }
}
