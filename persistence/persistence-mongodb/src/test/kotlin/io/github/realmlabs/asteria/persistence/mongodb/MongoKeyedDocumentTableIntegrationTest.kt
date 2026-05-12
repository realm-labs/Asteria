package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.persistence.Entity
import io.github.realmlabs.asteria.persistence.RowCachePolicy
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.codecs.pojo.annotations.BsonId
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.util.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MongoKeyedDocumentTableIntegrationTest {
    @Test
    fun `flushSome respects budget and keeps remaining dirty rows queued`() = withDatabase { database ->
        insertDocument(database, 1, "alice")
        insertDocument(database, 2, "bob")
        val table = table(database)

        table.use(1) { row -> row.name = "alice-1" }
        table.use(2) { row -> row.name = "bob-1" }

        val first = table.flushSome(MongoFlushBudget(maxRows = 1, maxDuration = 1.minutes))

        assertEquals(MongoFlushProgress(attemptedRows = 1, flushedRows = 1, failedRows = 0), first)
        assertEquals("alice-1", findDocument(database, 1).getString("name"))
        assertEquals("bob", findDocument(database, 2).getString("name"))

        val second = table.flushSome(MongoFlushBudget(maxRows = 1, maxDuration = 1.minutes))

        assertEquals(MongoFlushProgress(attemptedRows = 1, flushedRows = 1, failedRows = 0), second)
        assertEquals("bob-1", findDocument(database, 2).getString("name"))
    }

    @Test
    fun `idle unload flushes tracked row and invalidates leaked wrapper`() = withDatabase { database ->
        insertDocument(database, 1, "alice")
        val clock = MutableKeyedClock()
        val table = table(database, RowCachePolicy(1.seconds), clock)
        lateinit var leaked: KeyedTrackedDocument

        table.use(1) { row ->
            leaked = row
            row.name = "bob"
        }

        clock.advanceSeconds(2)
        table.unloadIdle()

        assertEquals(0, table.loadedCount())
        assertEquals("bob", findDocument(database, 1).getString("name"))
        assertFailsWith<IllegalStateException> {
            leaked.name = "carol"
        }
    }

    @Test
    fun `query APIs return keys and mapped snapshots`() = withDatabase { database ->
        insertDocument(database, 1, "alice")
        insertDocument(database, 2, "bob")
        val table = table(database)

        assertEquals(listOf(2), table.queryKeys(eq("name", "bob")))
        assertEquals(listOf("alice", "bob"), table.querySnapshots { row -> row.name }.sorted())
    }

    @Test
    fun `deleteByKey removes unloaded tracked document`() = withDatabase { database ->
        insertDocument(database, 1, "alice")
        val table = table(database)

        table.deleteByKey(1)

        assertNull(findDocumentOrNull(database, 1))
    }

    private fun table(
        database: MongoDatabase,
        cachePolicy: RowCachePolicy = RowCachePolicy(1.minutes),
        clock: Clock = Clock.System,
    ): KeyedTrackedTable {
        return KeyedTrackedTable(database, cachePolicy, clock)
    }

    private suspend fun insertDocument(database: MongoDatabase, id: Int, name: String) {
        database.getCollection<Document>(KEYED_COLLECTION).insertOne(Document(mapOf("_id" to id, "name" to name)))
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

    private suspend fun findDocument(database: MongoDatabase, id: Int): Document {
        return assertNotNull(findDocumentOrNull(database, id))
    }

    private suspend fun findDocumentOrNull(database: MongoDatabase, id: Int): Document? {
        return database.getCollection<Document>(KEYED_COLLECTION).find(eq("_id", id)).firstOrNull()
    }

    private companion object {
        private var mongoContainer: MongoDBContainer? = null

        fun mongo(): MongoDBContainer {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
            return mongoContainer ?: MongoDBContainer(DockerImageName.parse("mongo:7.0.14"))
                .also { container ->
                    container.start()
                    mongoContainer = container
                }
        }
    }
}

private class KeyedTrackedTable(
    database: MongoDatabase,
    cachePolicy: RowCachePolicy,
    clock: Clock,
) : MongoKeyedDocumentTable<Int, KeyedDocumentEntity, KeyedTrackedDocument>(
    collectionName = KEYED_COLLECTION,
    entityType = KeyedDocumentEntity::class,
    cachePolicy = cachePolicy,
    database = database,
    clock = clock,
) {
    override fun wrap(context: MongoTrackContext, entity: KeyedDocumentEntity): KeyedTrackedDocument {
        return KeyedTrackedDocument(context, entity)
    }
}

private class KeyedTrackedDocument(
    ctx: MongoTrackContext,
    entity: KeyedDocumentEntity,
) : MongoTrackedDocument<Int, KeyedDocumentEntity> {
    override val id: Int = entity.id
    var name: String by ctx.trackedValue("name", entity.name)

    override fun toEntity(): KeyedDocumentEntity {
        return KeyedDocumentEntity(id, name)
    }

    override fun toMongoValue(): Any? {
        return Document(mapOf("_id" to id, "name" to name))
    }
}

data class KeyedDocumentEntity(
    @param:BsonId
    override val id: Int,
    val name: String,
) : Entity<Int>

private class MutableKeyedClock : Clock {
    private var instant: Instant = Instant.fromEpochMilliseconds(0)

    override fun now(): Instant = instant

    fun advanceSeconds(seconds: Long) {
        instant += seconds.seconds
    }
}

private const val KEYED_COLLECTION = "keyed_documents"
