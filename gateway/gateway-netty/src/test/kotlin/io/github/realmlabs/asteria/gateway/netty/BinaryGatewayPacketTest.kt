package io.github.realmlabs.asteria.gateway.netty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryGatewayPacketTest {
    @Test
    fun `indexed binary packet codec round trips packet`() {
        val client = IndexedBinaryGatewayPacketCodec()
        val server = IndexedBinaryGatewayPacketCodec()
        val packet = BinaryGatewayPacket(1001, "payload".encodeToByteArray())

        val decoded = server.decode(client.encode(packet))

        assertEquals(packet, decoded)
    }

    @Test
    fun `indexed binary packet codec rejects out of order packet`() {
        val client = IndexedBinaryGatewayPacketCodec()
        val server = IndexedBinaryGatewayPacketCodec()

        client.encode(BinaryGatewayPacket(1, byteArrayOf()))
        val second = client.encode(BinaryGatewayPacket(2, byteArrayOf()))

        assertFailsWith<IllegalArgumentException> {
            server.decode(second)
        }
    }
}
