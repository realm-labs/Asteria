package io.github.realmlabs.asteria.gateway.netty

import io.github.realmlabs.asteria.gateway.*
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class NettyGatewayMessageHandlerTest {
    @Test
    fun `message handler delivers decoded netty message with gateway session`(): Unit = runBlocking {
        val received = CompletableDeferred<Pair<String, String>>()
        val transportHandler = object : GatewayTransportHandler {
            override suspend fun connected(connection: GatewayConnection): GatewaySession {
                return GatewaySession(GatewaySessionId("s1"), connection)
            }

            override suspend fun received(session: GatewaySession, frame: GatewayFrame) {
                error("frame path should not be used")
            }

            override suspend fun disconnected(session: GatewaySession, cause: Throwable?) = Unit
        }
        val handler = NettyGatewayMessageHandler(
            inboundType = String::class.java,
            transport = GatewayTransportKind.TCP,
            scope = this,
            handler = transportHandler,
            receiver = NettyGatewayMessageReceiver {
                received.complete(it.session.id.value to it.message)
            },
        )

        val channel = EmbeddedChannel(handler)
        channel.pipeline().fireChannelActive()
        channel.writeInbound("hello")

        assertEquals("s1" to "hello", withTimeout(1_000.milliseconds) { received.await() })
        channel.close()
    }
}
