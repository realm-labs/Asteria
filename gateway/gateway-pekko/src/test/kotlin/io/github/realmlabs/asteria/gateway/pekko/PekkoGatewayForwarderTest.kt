package io.github.realmlabs.asteria.gateway.pekko

import io.github.realmlabs.asteria.cluster.pekko.EntityShardRegistry
import io.github.realmlabs.asteria.cluster.pekko.SingletonActorRegistry
import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.SingletonName
import io.github.realmlabs.asteria.gateway.*
import io.github.realmlabs.asteria.message.RouteTarget
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import java.net.SocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PekkoGatewayForwarderTest {
    private val system = ActorSystem.create("asteria-gateway-forwarder-test-${System.nanoTime()}")

    @AfterTest
    fun tearDown() {
        Await.result(system.terminate(), Duration.Inf())
    }

    @Test
    fun `forwards entity route to shard registry`() = runBlocking {
        val probe = TestProbe(system)
        val shards = EntityShardRegistry().apply {
            register(EntityKind("player"), probe.ref())
        }
        val forwarder = PekkoGatewayForwarder(
            system = system,
            shards = shards,
            singletons = SingletonActorRegistry(),
            messageFactory = TestMessageFactory,
        )

        forwarder.forward(testContext(), GatewayRoute(RouteTarget.Entity(EntityKind("player")), "1001"), "login")

        assertEquals("entity:1001:login", probe.expectMsgClass(String::class.java))
    }

    @Test
    fun `forwards singleton route to singleton registry`() = runBlocking {
        val probe = TestProbe(system)
        val singletons = SingletonActorRegistry().apply {
            register(SingletonName("world"), probe.ref())
        }
        val forwarder = PekkoGatewayForwarder(
            system = system,
            shards = EntityShardRegistry(),
            singletons = singletons,
            messageFactory = TestMessageFactory,
        )

        forwarder.forward(testContext(), GatewayRoute(RouteTarget.Singleton(SingletonName("world"))), "sync")

        assertEquals("singleton:sync", probe.expectMsgClass(String::class.java))
    }

    @Test
    fun `handles gateway local route`() = runBlocking {
        var handled: String? = null
        val forwarder = PekkoGatewayForwarder<String>(
            system = system,
            shards = EntityShardRegistry(),
            singletons = SingletonActorRegistry(),
            localHandler = PekkoGatewayLocalHandler { _, _, packet -> handled = packet },
        )

        forwarder.forward(testContext(), GatewayRoute(RouteTarget.GatewayLocal), "ping")

        assertEquals("ping", handled)
    }
}

private object TestMessageFactory : PekkoGatewayMessageFactory<String> {
    override fun entityMessage(context: GatewaySessionContext, route: GatewayRoute, packet: String): Any {
        return "entity:${route.entityId}:$packet"
    }

    override fun singletonMessage(context: GatewaySessionContext, route: GatewayRoute, packet: String): Any {
        return "singleton:$packet"
    }

    override fun serviceMessage(context: GatewaySessionContext, route: GatewayRoute, packet: String): Any {
        return "service:$packet"
    }

    override fun localMessage(context: GatewaySessionContext, route: GatewayRoute, packet: String): Any {
        return "local:$packet"
    }
}

private fun testContext(): GatewaySessionContext {
    return GatewaySessionContext(
        GatewaySession(
            id = GatewaySessionId("s1"),
            connection = object : GatewayConnection {
                override val id: GatewayConnectionId = GatewayConnectionId("c1")
                override val transport: GatewayTransportKind = GatewayTransportKind.CUSTOM
                override val remoteAddress: SocketAddress? = null

                override fun write(frame: GatewayFrame) = Unit

                override fun close() = Unit
            },
        ),
    )
}
