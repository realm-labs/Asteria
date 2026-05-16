package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.persistence.Entity
import io.github.realmlabs.asteria.persistence.RowCachePolicy
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.codecs.pojo.annotations.BsonId
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.util.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MongoScannedKeyedDocumentTableIntegrationTest {
    @Test
    fun `createLoaded flushes an upserted Mongo document`() = withDatabase { database ->
        val table = table(database)

        table.createLoaded(TestEntity(1, "alice", linkedMapOf("a" to 1)))
        assertTrue(table.flush())

        val document = findPlayer(database, 1)
        assertEquals(1, document.getInteger("_id"))
        assertEquals("alice", document.getString("name"))
        assertEquals(Document("a", 1), document["bag"])
    }

    @Test
    fun `scan flush updates and unsets map keys in Mongo`() = withDatabase { database ->
        val table = table(database)
        val row = table.createLoaded(TestEntity(1, "alice", linkedMapOf("a" to 1, "b" to 2)))
        assertTrue(table.flush())

        row.bag.remove("b")
        row.bag["a"] = 3
        row.bag["c"] = 4
        assertTrue(table.flush())

        assertEquals(Document(mapOf("a" to 3, "c" to 4)), findPlayer(database, 1)["bag"])
    }

    @Test
    fun `deleteLoaded and deleteByKey remove Mongo documents`() = withDatabase { database ->
        val table = table(database)
        table.createLoaded(TestEntity(1, "alice", linkedMapOf("a" to 1)))
        table.createLoaded(TestEntity(2, "bob", linkedMapOf("b" to 2)))
        assertTrue(table.flush())

        assertTrue(table.deleteLoaded(1))
        assertTrue(table.deleteByKey(2))

        val remaining = database.getCollection<Document>(COLLECTION).find(Document()).toList()
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `failed flush keeps pending write queued`(): Unit = runBlocking {
        val client = MongoClient.create("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=50")
        client.use { client ->
            val runtime = runtimeFor(id = 1, database = client.getDatabase("unreachable"))

            runtime.enqueueCreated(TestEntity(1, "alice", linkedMapOf("a" to 1)))

            assertFalse(runtime.flushSafely())
            assertTrue(runtime.pendingWrites().isNotEmpty())
        }
    }

    @Test
    fun `journal recovery replays unflushed scan writes`() = withDatabase { database ->
        val directory = createTempDirectory("asteria-mongo-journal-it")
        val policy = MongoJournalPolicy(enabled = true, directory = directory, forceOnAppend = true)

        FileMongoWriteJournal(policy).use { journal ->
            runtimeFor(id = 1, database = database, journal = journal)
                .enqueueCreated(TestEntity(1, "alice", linkedMapOf("a" to 1)))
        }

        FileMongoWriteJournal(policy).use { journal ->
            val result = MongoJournalRecovery(journal, database).recover()

            assertEquals(2, result.entries)
            assertEquals(1, result.documents)
            assertTrue(journal.recover().isEmpty())
        }
        assertEquals("alice", findPlayer(database, 1).getString("name"))
    }

    @Test
    fun `idle unload flushes and unloads loaded scanned rows`() = withDatabase { database ->
        val clock = MutableTableClock()
        val table = table(database, RowCachePolicy(1.seconds), clock)
        val row = table.createLoaded(TestEntity(1, "alice", linkedMapOf("a" to 1)))
        row.name = "bob"

        clock.advanceSeconds(2)
        table.unloadIdle()

        assertEquals(0, table.loadedCount())
        assertEquals("bob", findPlayer(database, 1).getString("name"))
    }

    @Test
    fun `failed idle unload keeps loaded scanned row`(): Unit = runBlocking {
        val client = MongoClient.create("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=50")
        client.use { client ->
            val clock = MutableTableClock()
            val table = table(client.getDatabase("unreachable"), RowCachePolicy(1.seconds), clock)

            table.createLoaded(TestEntity(1, "alice", linkedMapOf("a" to 1)))
            clock.advanceSeconds(2)
            table.unloadIdle()

            assertEquals(1, table.loadedCount())
        }
    }

    @Test
    fun `map keys with Mongo path characters are encoded end to end`() = withDatabase { database ->
        val table = table(database)
        val row = table.createLoaded(TestEntity(1, "alice", linkedMapOf("a.b" to 1, $$"a$b" to 2, "a%b" to 3)))
        assertTrue(table.flush())

        val createdBag = findPlayer(database, 1).get("bag", Document::class.java)
        assertEquals(1, createdBag["a%2Eb"])
        assertEquals(2, createdBag["a%24b"])
        assertEquals(3, createdBag["a%25b"])

        row.bag.remove("a.b")
        row.bag[$$"a$b"] = 5
        assertTrue(table.flush())

        val updatedBag = findPlayer(database, 1).get("bag", Document::class.java)
        assertFalse(updatedBag.containsKey("a%2Eb"))
        assertEquals(5, updatedBag["a%24b"])
    }

    @Test
    fun `queryKeys returns matching keys`() = withDatabase { database ->
        val table = table(database)
        table.createLoaded(TestEntity(1, "alice", linkedMapOf("a" to 1)))
        table.createLoaded(TestEntity(2, "bob", linkedMapOf("b" to 2)))
        assertTrue(table.flush())

        assertEquals(listOf(2), table.queryKeys(eq("name", "bob")))
    }

    @Test
    fun `flushSome respects budget and drain flushes all scanned dirty rows`() = withDatabase { database ->
        val table = table(database)
        table.createLoaded(TestEntity(1, "alice", linkedMapOf("a" to 1)))
        table.createLoaded(TestEntity(2, "bob", linkedMapOf("b" to 2)))
        table.createLoaded(TestEntity(3, "carol", linkedMapOf("c" to 3)))

        val progress = table.flushSome(MongoFlushBudget(maxRows = 1, maxDuration = 1.minutes))

        assertEquals(MongoFlushProgress(attemptedRows = 1, flushedRows = 1, failedRows = 0), progress)
        assertEquals("alice", findPlayer(database, 1).getString("name"))
        assertNull(findPlayerOrNull(database, 2))
        assertNull(findPlayerOrNull(database, 3))

        assertTrue(table.drain())

        assertEquals("bob", findPlayer(database, 2).getString("name"))
        assertEquals("carol", findPlayer(database, 3).getString("name"))
    }

    @Test
    fun `journal recovery is idempotent when Mongo already has the same write`() = withDatabase { database ->
        val directory = createTempDirectory("asteria-mongo-journal-idempotent")
        val policy = MongoJournalPolicy(enabled = true, directory = directory, forceOnAppend = true)
        FileMongoWriteJournal(policy).use { journal ->
            runtimeFor(id = 1, database = database, journal = journal)
                .enqueueCreated(TestEntity(1, "alice", linkedMapOf("a" to 1)))
        }
        database.getCollection<Document>(COLLECTION).insertOne(
            Document(mapOf("_id" to 1, "name" to "alice", "bag" to Document("a", 1))),
        )

        FileMongoWriteJournal(policy).use { journal ->
            val result = MongoJournalRecovery(journal, database).recover()

            assertEquals(2, result.entries)
            assertEquals(1, result.documents)
            assertTrue(journal.recover().isEmpty())
        }
        assertEquals(Document("a", 1), findPlayer(database, 1)["bag"])
    }

    @Test
    fun `journal recovery merges multiple documents and delete wins`() = withDatabase { database ->
        val directory = createTempDirectory("asteria-mongo-journal-merge")
        val key = MongoDocumentKey(COLLECTION, 1)
        val policy = MongoJournalPolicy(enabled = true, directory = directory, forceOnAppend = true)
        database.getCollection<Document>(COLLECTION).insertOne(Document(mapOf("_id" to 1, "name" to "old")))

        FileMongoWriteJournal(policy).use { journal ->
            journal.append(MongoChangeOp.Set(key.path("name"), "alice"))
            journal.append(MongoChangeOp.Set(MongoDocumentKey(COLLECTION, 2).path("name"), "bob"))
            journal.append(MongoChangeOp.Delete(key))
            journal.append(MongoChangeOp.Set(key.path("name"), "ignored"))
        }

        FileMongoWriteJournal(policy).use { journal ->
            val result = MongoJournalRecovery(journal, database).recover()

            assertEquals(4, result.entries)
            assertEquals(2, result.documents)
        }
        assertNull(database.getCollection<Document>(COLLECTION).find(eq("_id", 1)).firstOrNull())
        assertEquals("bob", findPlayer(database, 2).getString("name"))
    }

    private fun table(
        database: MongoDatabase,
        cachePolicy: RowCachePolicy = RowCachePolicy(1.minutes),
        clock: Clock = Clock.System,
    ): MongoScannedKeyedDocumentTable<Int, TestEntity> {
        return MongoScannedKeyedDocumentTable(
            collectionName = COLLECTION,
            entityType = TestEntity::class,
            idType = Int::class,
            scanPlan = SCAN_PLAN,
            cachePolicy = cachePolicy,
            database = database,
            clock = clock,
        )
    }

    private fun runtimeFor(
        id: Int,
        database: MongoDatabase,
        journal: MongoWriteJournal = NoopMongoWriteJournal,
    ): MongoScannedDocumentRuntime<Int, TestEntity> {
        return MongoScannedDocumentRuntime(
            collectionName = COLLECTION,
            documentId = id,
            scanPlan = SCAN_PLAN,
            database = database,
            journal = journal,
        )
    }

    private fun withDatabase(block: suspend (MongoDatabase) -> Unit) = runBlocking {
        MongoClient.create(mongo().connectionString).use { client ->
            val database = client.getDatabase("asteria_${UUID.randomUUID().toString().replace("-", "")}")
            try {
                block(database)
            } finally {
                database.drop()
            }
        }
    }

    private suspend fun findPlayer(database: MongoDatabase, id: Int): Document {
        return assertNotNull(findPlayerOrNull(database, id))
    }

    private suspend fun findPlayerOrNull(database: MongoDatabase, id: Int): Document? {
        return database.getCollection<Document>(COLLECTION).find(eq("_id", id)).firstOrNull()
    }

    data class TestEntity(
        @param:BsonId
        override val id: Int,
        var name: String,
        var bag: MutableMap<String, Int>,
    ) : Entity<Int>

    private class MutableTableClock : Clock {
        private var instant: Instant = Instant.fromEpochMilliseconds(0)

        override fun now(): Instant = instant

        fun advanceSeconds(seconds: Long) {
            instant += seconds.seconds
        }
    }

    private companion object {
        const val COLLECTION = "players"

        private var mongoContainer: MongoDBContainer? = null

        fun mongo(): MongoDBContainer {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
            return mongoContainer ?: MongoDBContainer(DockerImageName.parse("mongo:7.0.14"))
                .also { container ->
                    container.start()
                    mongoContainer = container
                }
        }

        val SCAN_PLAN = mongoScanPlan(
            mongoScannedField("name") { entity -> entity.name },
            mongoScannedMapField<TestEntity>("bag") { entity -> entity.bag },
        )
    }
}
