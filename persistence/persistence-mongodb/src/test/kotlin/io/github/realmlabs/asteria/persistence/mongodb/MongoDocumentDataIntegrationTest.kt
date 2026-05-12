package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.ServiceRegistry
import io.github.realmlabs.asteria.persistence.*
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

class MongoDocumentDataIntegrationTest {
    @Test
    fun `tracked document data works under DataManager flush and drain`() = withDatabase { database ->
        val manager = DataManager(
            scope = scope(1),
            modules = listOf(dataModule(bucket = DataBucket.lazy()) {
                TestTrackedDocumentData(it, database)
            }),
        )
        val data = manager.getOrLoad<TestTrackedDocumentData>()

        data.create(TestDocumentEntity(1, "alice"))
        assertTrue(manager.flush())
        assertEquals("alice", findDocument(database, 1).getString("name"))

        data.rename("bob")
        assertTrue(manager.drain())
        assertEquals("bob", findDocument(database, 1).getString("name"))

        assertTrue(data.delete())
        assertNull(findDocumentOrNull(database, 1))
    }

    @Test
    fun `scanned document data works under DataManager flush and drain`() = withDatabase { database ->
        val manager = DataManager(
            scope = scope(1),
            modules = listOf(dataModule(bucket = DataBucket.lazy()) {
                TestScannedDocumentData(it, database)
            }),
        )
        val data = manager.getOrLoad<TestScannedDocumentData>()

        data.create(TestDocumentEntity(1, "alice"))
        assertTrue(manager.flush())
        assertEquals("alice", findDocument(database, 1).getString("name"))

        data.rename("bob")
        assertTrue(manager.drain())
        assertEquals("bob", findDocument(database, 1).getString("name"))
    }

    @Test
    fun `scanned document data loads existing document and tick persists scan changes`() = withDatabase { database ->
        database.getCollection<Document>(DOCUMENT_COLLECTION).insertOne(Document(mapOf("_id" to 1, "name" to "alice")))
        val manager = DataManager(
            scope = scope(1),
            modules = listOf(dataModule(bucket = DataBucket.lazy()) {
                TestScannedDocumentData(it, database)
            }),
        )
        val data = manager.getOrLoad<TestScannedDocumentData>()

        assertEquals("alice", data.currentName())
        data.rename("carol")
        manager.tick()

        assertEquals("carol", findDocument(database, 1).getString("name"))
    }

    private fun scope(id: Int): DataScope<Int> {
        return DataScope(EntityKind("player"), id, ServiceRegistry())
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
        return database.getCollection<Document>(DOCUMENT_COLLECTION).find(eq("_id", id)).firstOrNull()
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

private class TestTrackedDocumentData(
    scope: DataScope<Int>,
    database: MongoDatabase,
) : MongoTrackedDocumentData<Int, TestDocumentEntity, TestTrackedDocument>(
    scope = scope,
    collectionName = DOCUMENT_COLLECTION,
    entityType = TestDocumentEntity::class,
    wrapper = ::TestTrackedDocument,
    database = database,
) {
    fun create(entity: TestDocumentEntity): TestTrackedDocument = createTracked(entity)

    fun rename(name: String) {
        requireValue().name = name
    }

    suspend fun delete(): Boolean = deleteValue()
}

private class TestScannedDocumentData(
    scope: DataScope<Int>,
    database: MongoDatabase,
) : MongoScannedDocumentData<Int, TestDocumentEntity>(
    scope = scope,
    collectionName = DOCUMENT_COLLECTION,
    entityType = TestDocumentEntity::class,
    scanPlan = DOCUMENT_SCAN_PLAN,
    database = database,
) {
    fun create(entity: TestDocumentEntity): TestDocumentEntity = createScanned(entity)

    fun rename(name: String) {
        requireValue().name = name
    }

    fun currentName(): String = requireValue().name
}

private class TestTrackedDocument(
    ctx: MongoTrackContext,
    entity: TestDocumentEntity,
) : MongoTrackedDocument<Int, TestDocumentEntity> {
    override val id: Int = entity.id
    var name: String by ctx.trackedValue("name", entity.name)

    override fun toEntity(): TestDocumentEntity {
        return TestDocumentEntity(id, name)
    }

    override fun toMongoValue(): Any? {
        return Document(mapOf("_id" to id, "name" to name))
    }
}

data class TestDocumentEntity(
    @param:BsonId
    override val id: Int,
    var name: String,
) : Entity<Int>

private const val DOCUMENT_COLLECTION = "documents"

private val DOCUMENT_SCAN_PLAN = mongoScanPlan(
    mongoScannedField<TestDocumentEntity>("name") { entity -> entity.name },
)
