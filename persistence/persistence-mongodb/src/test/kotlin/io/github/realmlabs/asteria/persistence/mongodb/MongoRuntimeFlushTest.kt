package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.bulk.BulkWriteResult
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.persistence.DataLease
import io.github.realmlabs.asteria.persistence.Entity
import io.github.realmlabs.asteria.persistence.mongodb.common.MongoDocumentKey
import io.github.realmlabs.asteria.persistence.mongodb.common.MongoPath
import io.github.realmlabs.asteria.persistence.mongodb.scanned.MongoScannedDocumentRuntime
import io.github.realmlabs.asteria.persistence.mongodb.scanned.mongoScanPlan
import io.github.realmlabs.asteria.persistence.mongodb.scanned.mongoScannedField
import io.github.realmlabs.asteria.persistence.mongodb.tracked.MongoTrackedDocumentRuntime
import io.github.realmlabs.asteria.persistence.mongodb.write.*
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.mongodb.reactivestreams.client.MongoCollection as ReactiveMongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase as ReactiveMongoDatabase

class MongoRuntimeFlushTest {
    @Test
    fun `flusher requeues failed writes and retries them without losing journal sequences`(): Unit = runBlocking {
        val collection = FailingOnceBulkWriteCollection()
        val database = fakeDatabase(collection.proxy())
        val journal = RecordingMongoWriteJournal()
        val queue = MongoPendingWriteQueue(journal = journal)
        val flusher = MongoPendingWriteFlusher(queue, database, journal)

        queue.enqueue(MongoChangeOp.Set(MongoPath("players", 1001, "name"), "alice"))

        assertFalse(runCatching { flusher.flush() }.isSuccess)
        assertEquals(1, collection.bulkWriteCalls)
        assertEquals(
            MongoPendingWrite(
                key = MongoDocumentKey("players", 1001),
                sets = mapOf("name" to "alice"),
                journalSequences = setOf(1L),
            ),
            queue.snapshot().single(),
        )
        assertTrue(journal.acked.isEmpty())

        assertEquals(1, flusher.flush().size)

        assertEquals(2, collection.bulkWriteCalls)
        assertTrue(queue.snapshot().isEmpty())
        assertEquals(listOf(1L), journal.acked)
    }

    @Test
    fun `tracked document runtime keeps pending writes when bound lease is inactive`(): Unit = runBlocking {
        val runtime = MongoTrackedDocumentRuntime("players", 1001, fakeDatabase(NoopBulkWriteCollection.proxy()))
        runtime.bindLease(invalidatedLease())
        runtime.queue.enqueue(MongoChangeOp.Set(MongoPath("players", 1001, "name"), "alice"))

        assertFalse(runtime.flushSafely())

        assertEquals(
            MongoPendingWrite(MongoDocumentKey("players", 1001), sets = mapOf("name" to "alice")),
            runtime.pendingWrites().single(),
        )
    }

    @Test
    fun `scanned document runtime keeps pending writes when bound lease is inactive`(): Unit = runBlocking {
        val runtime = MongoScannedDocumentRuntime(
            collectionName = "players",
            documentId = 1001,
            scanPlan = mongoScanPlan(
                mongoScannedField<TestEntity>("name") { entity -> entity.name },
            ),
            database = fakeDatabase(NoopBulkWriteCollection.proxy()),
        )
        runtime.bindLease(invalidatedLease())
        runtime.enqueueCreated(TestEntity(1001, "alice"))

        assertFalse(runtime.flushSafely())

        assertEquals(
            MongoPendingWrite(MongoDocumentKey("players", 1001), sets = mapOf("name" to "alice")),
            runtime.pendingWrites().single(),
        )
    }

    private fun fakeDatabase(collection: ReactiveMongoCollection<Document>): MongoDatabase {
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

    private fun invalidatedLease(): DataLease {
        val constructor = DataLease::class.java.getDeclaredConstructor(String::class.java)
        constructor.isAccessible = true
        val lease = constructor.newInstance("test lease")
        val invalidate = DataLease::class.java.declaredMethods.single { method -> method.name.startsWith("invalidate") }
        invalidate.isAccessible = true
        invalidate.invoke(lease)
        return lease
    }

    private class RecordingMongoWriteJournal : MongoWriteJournal {
        private var nextSequence = 1L
        val acked: MutableList<Long> = mutableListOf()

        override fun append(op: MongoChangeOp): Long = nextSequence++

        override fun ack(sequences: Iterable<Long>) {
            acked += sequences
        }

        override fun recover(): List<MongoJournalEntry> = emptyList()
    }

    private class FailingOnceBulkWriteCollection {
        var bulkWriteCalls: Int = 0
            private set

        @Suppress("UNCHECKED_CAST")
        fun proxy(): ReactiveMongoCollection<Document> {
            return Proxy.newProxyInstance(
                ReactiveMongoCollection::class.java.classLoader,
                arrayOf(ReactiveMongoCollection::class.java),
            ) { proxy, method, _ ->
                when (method.name) {
                    "bulkWrite" -> {
                        bulkWriteCalls += 1
                        if (bulkWriteCalls == 1) {
                            ErrorPublisher(IllegalStateException("first bulk write failed"))
                        } else {
                            ValuePublisher(acknowledgedBulkWriteResult())
                        }
                    }

                    "withDocumentClass",
                    "withCodecRegistry",
                    "withReadPreference",
                    "withWriteConcern",
                    "withReadConcern",
                    "withTimeout",
                        -> proxy

                    "toString" -> "FailingOnceBulkWriteCollection"
                    else -> error("unexpected Mongo collection call ${method.name}")
                }
            } as ReactiveMongoCollection<Document>
        }
    }

    private object NoopBulkWriteCollection {
        @Suppress("UNCHECKED_CAST")
        fun proxy(): ReactiveMongoCollection<Document> {
            return Proxy.newProxyInstance(
                ReactiveMongoCollection::class.java.classLoader,
                arrayOf(ReactiveMongoCollection::class.java),
            ) { proxy, method, _ ->
                when (method.name) {
                    "bulkWrite" -> ValuePublisher(acknowledgedBulkWriteResult())
                    "withDocumentClass",
                    "withCodecRegistry",
                    "withReadPreference",
                    "withWriteConcern",
                    "withReadConcern",
                    "withTimeout",
                        -> proxy

                    "toString" -> "NoopBulkWriteCollection"
                    else -> error("unexpected Mongo collection call ${method.name}")
                }
            } as ReactiveMongoCollection<Document>
        }
    }

    private class ValuePublisher<T>(
        private val value: T,
    ) : Publisher<T> {
        override fun subscribe(subscriber: Subscriber<in T>) {
            subscriber.onSubscribe(
                object : Subscription {
                    private var done = false

                    override fun request(n: Long) {
                        if (done) return
                        done = true
                        subscriber.onNext(value)
                        subscriber.onComplete()
                    }

                    override fun cancel() {
                        done = true
                    }
                },
            )
        }
    }

    private class ErrorPublisher<T>(
        private val error: Throwable,
    ) : Publisher<T> {
        override fun subscribe(subscriber: Subscriber<in T>) {
            subscriber.onSubscribe(
                object : Subscription {
                    private var done = false

                    override fun request(n: Long) {
                        if (done) return
                        done = true
                        subscriber.onError(error)
                    }

                    override fun cancel() {
                        done = true
                    }
                },
            )
        }
    }

    private data class TestEntity(
        override val id: Int,
        val name: String,
    ) : Entity<Int>

    private companion object {
        fun acknowledgedBulkWriteResult(): BulkWriteResult {
            return BulkWriteResult.acknowledged(0, 1, 0, 1, emptyList(), emptyList())
        }
    }
}
