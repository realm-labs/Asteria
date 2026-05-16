package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.persistence.Entity
import io.github.realmlabs.asteria.persistence.mongodb.common.MongoDocumentKey
import io.github.realmlabs.asteria.persistence.mongodb.scanned.MongoScannedDocumentRuntime
import io.github.realmlabs.asteria.persistence.mongodb.scanned.mongoScanPlan
import io.github.realmlabs.asteria.persistence.mongodb.scanned.mongoScannedField
import io.github.realmlabs.asteria.persistence.mongodb.scanned.mongoScannedMapField
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
    fun `repeated scans merge to latest sets and remaining unsets`() {
        val runtime = runtimeFor(1)
        val entity = TestEntity(1, "alice", mapOf("a" to 1, "b" to 2))
        runtime.attachLoaded(entity)

        entity.bag = linkedMapOf("a" to 3, "c" to 4)
        runtime.scan(entity)
        entity.bag = linkedMapOf("a" to 5, "d" to 6)
        runtime.scan(entity)

        val write = runtime.pendingWrites().single()
        assertEquals(mapOf("bag.a" to 5, "bag.d" to 6), write.sets)
        assertEquals(setOf("bag.b", "bag.c"), write.unsets)
    }

    @Test
    fun `create and later scan merge into one upsert with latest values`() {
        val runtime = runtimeFor(1)
        val entity = TestEntity(1, "alice", linkedMapOf("a" to 1, "b" to 2))

        runtime.enqueueCreated(entity)
        entity.name = "bob"
        entity.bag = linkedMapOf("a" to 3, "c" to 4)
        runtime.scan(entity)

        val write = runtime.pendingWrites().single()
        assertEquals(mapOf("name" to "bob", "bag.a" to 3, "bag.c" to 4), write.sets)
        assertEquals(setOf("bag.b"), write.unsets)
        assertTrue(write.upsert)
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

    @Test
    fun `delete suppresses later scanned updates`() {
        val runtime = runtimeFor(1)
        val entity = TestEntity(1, "alice", mapOf("a" to 1))
        runtime.attachLoaded(entity)

        runtime.enqueueDelete()
        entity.name = "bob"
        entity.bag = mapOf("a" to 2)
        runtime.scan(entity)

        val write = runtime.pendingWrites().single()
        assertTrue(write.delete)
        assertTrue(write.sets.isEmpty())
        assertTrue(write.unsets.isEmpty())
    }

    @Test
    fun `created child fields are replaced by latest scanned child values before first flush`() {
        val runtime = runtimeFor(1)
        val entity = TestEntity(1, "alice", linkedMapOf("a" to 1))

        runtime.enqueueCreated(entity)
        entity.bag = linkedMapOf("a" to 2)
        runtime.scan(entity)

        val write = runtime.pendingWrites().single()
        assertEquals(mapOf("name" to "alice", "bag.a" to 2), write.sets)
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
