package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.ScriptExecutionResult
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import scala.concurrent.duration.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration as KotlinDuration

internal class ScriptResultCollectorActor(
    private val executionId: String,
    private val future: CompletableFuture<List<ScriptExecutionResult>>,
    private val timeout: KotlinDuration,
) : AbstractActor() {
    private val results: MutableList<ScriptExecutionResult> = mutableListOf()

    override fun preStart() {
        context.system.scheduler().scheduleOnce(
            Duration.create(timeout.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS),
            self,
            Complete,
            context.dispatcher,
            self,
        )
    }

    override fun postStop() {
        if (!future.isDone) {
            future.complete(results.toList())
        }
        super.postStop()
    }

    override fun createReceive(): Receive {
        return receiveBuilder()
            .match(ScriptExecutionResult::class.java) {
                if (it.executionId == executionId) {
                    results.add(it)
                }
            }
            .matchEquals(Complete) {
                future.complete(results.toList())
                context.stop(self)
            }
            .build()
    }

    private companion object {
        data object Complete
    }
}

internal fun ActorSystem.collectScriptResults(
    target: ActorRef,
    command: ScriptExecutionCommand,
    timeout: KotlinDuration,
): CompletableFuture<List<ScriptExecutionResult>> {
    val future = CompletableFuture<List<ScriptExecutionResult>>()
    val collector = actorOf(
        Props.create(ScriptResultCollectorActor::class.java) {
            ScriptResultCollectorActor(command.executionId, future, timeout)
        },
    )
    target.tell(command, collector)
    return future
}
