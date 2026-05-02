package io.github.mikai233.asteria.rpc

import io.github.mikai233.asteria.core.EntityKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RpcMethodRegistryTest {
    @Test
    fun `method registry indexes methods by id name and request type`() {
        val method = RpcMethod(
            id = 1,
            name = "player.query",
            requestType = String::class,
            responseType = Int::class,
            target = RpcTarget.Entity(EntityKind("player")),
            mode = RpcMode.ASK,
            entityIdResolver = { it },
        )
        val registry = StaticRpcMethodRegistry(listOf(method))

        assertEquals(method, registry.methodFor(1))
        assertEquals(method, registry.methodNamed("player.query"))
        assertEquals(method, registry.methodForRequest(String::class))
        assertEquals("p1", method.resolveEntityId("p1"))
    }

    @Test
    fun `method registry rejects duplicate ids names and request types`() {
        assertFailsWith<IllegalStateException> {
            StaticRpcMethodRegistry(
                listOf(
                    testMethod(id = 1, name = "a", requestType = String::class),
                    testMethod(id = 1, name = "b", requestType = Int::class),
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            StaticRpcMethodRegistry(
                listOf(
                    testMethod(id = 1, name = "same", requestType = String::class),
                    testMethod(id = 2, name = "same", requestType = Int::class),
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            StaticRpcMethodRegistry(
                listOf(
                    testMethod(id = 1, name = "a", requestType = String::class),
                    testMethod(id = 2, name = "b", requestType = String::class),
                ),
            )
        }
    }

    private fun <Req : Any> testMethod(
        id: Int,
        name: String,
        requestType: kotlin.reflect.KClass<Req>,
    ): RpcMethod<Req, Unit> {
        return RpcMethod(
            id = id,
            name = name,
            requestType = requestType,
            responseType = null,
            target = RpcTarget.Singleton(io.github.mikai233.asteria.core.SingletonName("world")),
            mode = RpcMode.TELL,
        )
    }
}
