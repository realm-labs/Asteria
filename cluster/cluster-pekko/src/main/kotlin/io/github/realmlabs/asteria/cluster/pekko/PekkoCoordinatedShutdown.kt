package io.github.realmlabs.asteria.cluster.pekko

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.CoordinatedShutdown
import java.util.function.Supplier
import kotlin.coroutines.CoroutineContext

/**
 * Registers a suspend task in Pekko coordinated shutdown.
 *
 * Pekko expects shutdown tasks to return `CompletionStage<Done>`. This helper keeps application shutdown code in normal
 * suspend style while still propagating failures back to the coordinated shutdown pipeline.
 */
fun CoordinatedShutdown.addSuspendTask(
    phase: String,
    taskName: String,
    coroutineContext: CoroutineContext = Dispatchers.Default,
    task: suspend () -> Unit,
): CoordinatedShutdown {
    addTask(
        phase,
        taskName,
        Supplier {
            CoroutineScope(coroutineContext).future {
                task()
                Done.done()
            }
        },
    )
    return this
}

/**
 * Convenience entry for registering suspend shutdown work from an [ActorSystem].
 */
fun ActorSystem.addCoordinatedShutdownSuspendTask(
    phase: String,
    taskName: String,
    coroutineContext: CoroutineContext = Dispatchers.Default,
    task: suspend () -> Unit,
): CoordinatedShutdown {
    return CoordinatedShutdown.get(this).addSuspendTask(phase, taskName, coroutineContext, task)
}
