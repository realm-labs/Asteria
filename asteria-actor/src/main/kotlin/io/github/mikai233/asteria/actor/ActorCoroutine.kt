package io.github.mikai233.asteria.actor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.apache.pekko.actor.ActorRef
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

data class ActorCoroutineRunnable(val runnable: Runnable) : Runnable by runnable

fun ActorRef.safeActorCoroutineScope(): TrackingCoroutineScope {
    val dispatcher = Executor { tell(ActorCoroutineRunnable(it), ActorRef.noSender()) }.asCoroutineDispatcher()
    return TrackingCoroutineScope(dispatcher + SupervisorJob())
}

class TrackingCoroutineScope(context: CoroutineContext) : CoroutineScope {
    private val rootJob = Job(context[Job])
    override val coroutineContext: CoroutineContext = context + rootJob

    private val jobs = ConcurrentHashMap.newKeySet<Job>()

    fun trackedLaunch(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val job = launch(context, start, block)
        jobs.add(job)
        job.invokeOnCompletion { jobs.remove(job) }
        return job
    }

    fun activeJobs(): Set<Job> = jobs

    fun cancel() {
        rootJob.cancel()
    }
}
