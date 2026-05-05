package io.github.realmlabs.asteria.cluster.pekko

import io.github.realmlabs.asteria.core.gameApplication
import io.github.realmlabs.asteria.message.ShardMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.Props
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PekkoEntityWakerModuleTest {
    @Test
    fun `wakes configured sharded entity ids`() = runBlocking {
        val awakened = ConcurrentHashMap.newKeySet<Long>()
        val app = gameApplication {
            name = "asteria-entity-waker-test-${System.nanoTime()}"
            role("world")
            entity<Long>("world") {
                role("world")
                handoffMessage = StopWakeEntity
                actor { _, _ -> Props.create(WakeEntityActor::class.java) { WakeEntityActor(awakened) } }
                extractor(PekkoShardExtractors.longShardMessageByModulo(8))
            }
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
            install(
                PekkoEntityWakerModule {
                    coordinatorRole("world")
                    task<Long>("world") {
                        kind("world")
                        targets { listOf(101L, 102L, 103L) }
                        message { WakeEntity(it) }
                        success { it is WakeEntitySucceeded }
                        concurrency {
                            initial = 2
                            min = 1
                            max = 4
                            adjustmentWindow = 2
                        }
                        retry {
                            timeout = 2.seconds
                            initialDelay = 50.milliseconds
                            maxDelay = 50.milliseconds
                        }
                        readiness {
                            role("world")
                            minUpRatio = 1.0
                        }
                    }
                },
            )
        }

        app.launch()
        try {
            withTimeout(10.seconds) {
                while (!awakened.containsAll(listOf(101L, 102L, 103L))) {
                    kotlinx.coroutines.delay(50.milliseconds)
                }
            }
            assertEquals(setOf(101L, 102L, 103L), awakened)
        } finally {
            app.stop()
        }
    }

    @Test
    fun `backs off after a target exhausts retry attempts`() = runBlocking {
        val attempts = AtomicInteger()
        val app = gameApplication {
            name = "asteria-entity-waker-retry-test-${System.nanoTime()}"
            role("world")
            entity<Long>("world") {
                role("world")
                handoffMessage = StopWakeEntity
                actor { _, _ -> Props.create(FailingWakeEntityActor::class.java) { FailingWakeEntityActor(attempts) } }
                extractor(PekkoShardExtractors.longShardMessageByModulo(8))
            }
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
            install(
                PekkoEntityWakerModule {
                    coordinatorRole("world")
                    task<Long>("world") {
                        kind("world")
                        targets { listOf(201L) }
                        message { WakeEntity(it) }
                        success { it is WakeEntitySucceeded }
                        retry {
                            timeout = 500.milliseconds
                            initialDelay = 50.milliseconds
                            maxDelay = 50.milliseconds
                            maxAttempts = 2
                            exhaustedDelay = 1.seconds
                        }
                    }
                },
            )
        }

        app.launch()
        try {
            withTimeout(10.seconds) {
                while (attempts.get() < 2) {
                    kotlinx.coroutines.delay(25.milliseconds)
                }
            }
            kotlinx.coroutines.delay(250.milliseconds)

            assertEquals(2, attempts.get())
            val status = app.services.get<PekkoEntityWaker>().status("world")
            assertEquals(1, status.tasks.single().exhausted)
            assertEquals("201", status.tasks.single().targets.exhausted.single().targetId)
        } finally {
            app.stop()
        }
    }

    @Test
    fun `reconcile wakes targets added by source changes`() = runBlocking {
        val sourceTargets = ConcurrentHashMap.newKeySet<Long>().apply { add(401L) }
        val awakened = ConcurrentHashMap.newKeySet<Long>()
        val app = gameApplication {
            name = "asteria-entity-waker-increment-test-${System.nanoTime()}"
            role("world")
            entity<Long>("world") {
                role("world")
                handoffMessage = StopWakeEntity
                actor { _, _ -> Props.create(WakeEntityActor::class.java) { WakeEntityActor(awakened) } }
                extractor(PekkoShardExtractors.longShardMessageByModulo(8))
            }
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
            install(
                PekkoEntityWakerModule {
                    coordinatorRole("world")
                    task<Long>("world") {
                        kind("world")
                        targets { sourceTargets.toList() }
                        message { WakeEntity(it) }
                        success { it is WakeEntitySucceeded }
                        retry {
                            timeout = 2.seconds
                            initialDelay = 50.milliseconds
                            maxDelay = 50.milliseconds
                        }
                    }
                },
            )
        }

        app.launch()
        try {
            withTimeout(10.seconds) {
                while (!awakened.contains(401L)) {
                    kotlinx.coroutines.delay(25.milliseconds)
                }
            }

            sourceTargets += 402L
            app.services.get<PekkoEntityWaker>().reconcile()
            withTimeout(10.seconds) {
                while (!awakened.containsAll(listOf(401L, 402L))) {
                    kotlinx.coroutines.delay(25.milliseconds)
                }
            }

            val status = app.services.get<PekkoEntityWaker>().status("world")
            assertEquals(2, status.tasks.single().completed)
            assertEquals(setOf(401L, 402L), awakened)
        } finally {
            app.stop()
        }
    }

    @Test
    fun `manual control can cancel and wake targets`() = runBlocking {
        val awakened = ConcurrentHashMap.newKeySet<Long>()
        val app = gameApplication {
            name = "asteria-entity-waker-control-test-${System.nanoTime()}"
            role("world")
            entity<Long>("world") {
                role("world")
                handoffMessage = StopWakeEntity
                actor { _, _ -> Props.create(WakeEntityActor::class.java) { WakeEntityActor(awakened) } }
                extractor(PekkoShardExtractors.longShardMessageByModulo(8))
            }
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
            install(
                PekkoEntityWakerModule {
                    coordinatorRole("world")
                    reconcileOnStart = false
                    task<Long>("world") {
                        kind("world")
                        targets { listOf(301L) }
                        message { WakeEntity(it) }
                        success { it is WakeEntitySucceeded }
                        retry {
                            timeout = 2.seconds
                            initialDelay = 50.milliseconds
                            maxDelay = 50.milliseconds
                        }
                    }
                },
            )
        }

        app.launch()
        try {
            val waker = app.services.get<PekkoEntityWaker>()
            waker.cancel("world", listOf(301L))
            waker.reconcile()
            kotlinx.coroutines.delay(300.milliseconds)

            assertEquals(emptySet(), awakened)

            waker.wake("world", listOf(301L))
            withTimeout(10.seconds) {
                while (!awakened.contains(301L)) {
                    kotlinx.coroutines.delay(25.milliseconds)
                }
            }
            assertEquals(setOf(301L), awakened)
        } finally {
            app.stop()
        }
    }
}

private data class WakeEntity(
    override val id: Long,
) : ShardMessage<Long>, Serializable

private data class WakeEntitySucceeded(
    val id: Long,
) : Serializable

private data class WakeEntityRejected(
    val id: Long,
) : Serializable

private data object StopWakeEntity : Serializable

private class WakeEntityActor(
    private val awakened: MutableSet<Long>,
) : AbstractActor() {
    override fun createReceive(): Receive {
        return receiveBuilder()
            .match(WakeEntity::class.java) {
                awakened += it.id
                sender.tell(WakeEntitySucceeded(it.id), self)
            }
            .matchEquals(StopWakeEntity) {
                context.stop(self)
            }
            .build()
    }
}

private class FailingWakeEntityActor(
    private val attempts: AtomicInteger,
) : AbstractActor() {
    override fun createReceive(): Receive {
        return receiveBuilder()
            .match(WakeEntity::class.java) {
                attempts.incrementAndGet()
                sender.tell(WakeEntityRejected(it.id), self)
            }
            .matchEquals(StopWakeEntity) {
                context.stop(self)
            }
            .build()
    }
}
