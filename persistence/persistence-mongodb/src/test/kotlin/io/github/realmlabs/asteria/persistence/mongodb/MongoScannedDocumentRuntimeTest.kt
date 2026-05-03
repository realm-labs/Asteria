package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.persistence.Entity
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.mongodb.reactivestreams.client.MongoCollection as ReactiveMongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase as ReactiveMongoDatabase

class MongoScannedDocumentRuntimeTest {
    @Test
    fun `created scanned document enqueues all fields as upsert`() {
        val runtime = runtimeFor(1)

        runtime.enqueueCreated(TestEntity(1, "alice", mapOf("a" to 1)))

        val write = runtime.pendingWrites().single()
        assertEquals(MongoDocumentKey("players", 1), write.key)
        assertEquals(mapOf("name" to "alice", "bag.a" to 1), write.sets)
        assertTrue(write.unsets.isEmpty())
        assertTrue(write.upsert)
    }

    @Test
    fun `scan enqueues changed map keys`() {
        val runtime = runtimeFor(1)
        val entity = TestEntity(1, "alice", mapOf("a" to 1, "b" to 2))
        runtime.attachLoaded(entity)

        entity.bag = mapOf("a" to 3)
        runtime.scan(entity)

        val write = runtime.pendingWrites().single()
        assertEquals(mapOf("bag.a" to 3), write.sets)
        assertEquals(setOf("bag.b"), write.unsets)
    }

    @Test
    fun `delete overrides pending scanned updates`() {
        val runtime = runtimeFor(1)
        val entity = TestEntity(1, "alice", mapOf("a" to 1))
        runtime.attachLoaded(entity)

        entity.name = "bob"
        runtime.scan(entity)
        runtime.enqueueDelete()

        val write = runtime.pendingWrites().single()
        assertTrue(write.delete)
        assertTrue(write.sets.isEmpty())
        assertTrue(write.unsets.isEmpty())
    }

    private fun runtimeFor(id: Int): MongoScannedDocumentRuntime<Int, TestEntity> {
        return MongoScannedDocumentRuntime(
            collectionName = "players",
            documentId = id,
            scanPlan = mongoScanPlan(
                mongoScannedField<TestEntity>("name") { entity -> entity.name },
                mongoScannedMapField<TestEntity>("bag") { entity -> entity.bag },
            ),
            database = fakeDatabase(),
        )
    }

    private fun fakeDatabase(): MongoDatabase {
        val collection = Proxy.newProxyInstance(
            ReactiveMongoCollection::class.java.classLoader,
            arrayOf(ReactiveMongoCollection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "FakeReactiveMongoCollection"
                else -> error("unexpected Mongo collection call ${method.name}")
            }
        }
        val database = Proxy.newProxyInstance(
            ReactiveMongoDatabase::class.java.classLoader,
            arrayOf(ReactiveMongoDatabase::class.java),
        ) { proxy, method, _ ->
            when (method.name) {
                "getCollection" -> collection
                "getName" -> "test"
                "withCodecRegistry",
                "withReadPreference",
                "withWriteConcern",
                "withReadConcern",
                "withTimeout",
                    -> proxy

                "toString" -> "FakeReactiveMongoDatabase"
                else -> error("unexpected Mongo database call ${method.name}")
            }
        } as ReactiveMongoDatabase
        return MongoDatabase(database)
    }

    private data class TestEntity(
        override val id: Int,
        var name: String,
        var bag: Map<String, Int>,
    ) : Entity<Int>
}
