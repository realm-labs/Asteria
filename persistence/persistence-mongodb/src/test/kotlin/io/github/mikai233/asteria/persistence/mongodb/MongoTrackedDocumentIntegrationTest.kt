package io.github.mikai233.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.persistence.Entity
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MongoTrackedDocumentIntegrationTest {
    @Test
    fun `tracked wrapper writes scalar nested collection and remove updates`() = withDatabase { database ->
        val runtime = runtimeFor(1, database)
        val player = TrackedPlayer(
            runtime.context(),
            PlayerEntity(
                id = 1,
                name = "alice",
                profile = Profile("alice", 1),
                bag = linkedMapOf("sword" to ItemStack(1001, 1), "potion" to ItemStack(2001, 2)),
                quests = mutableListOf(QuestState(10, "open")),
                tags = linkedSetOf("newbie", "vip"),
            ),
        )

        runtime.enqueueCreated(player)
        assertTrue(runtime.flushSafely())

        player.name = "bob"
        player.profile.nickname = "bobby"
        player.bag.getValue("potion").count = 7
        player.bag.remove("sword")
        player.quests[0].status = "done"
        player.tags.remove("newbie")
        player.tags.add("returning")
        assertTrue(runtime.flushSafely())

        val document = findPlayer(database, 1)
        assertEquals("bob", document.getString("name"))
        assertEquals(Document(mapOf("nickname" to "bobby", "avatar" to 1)), document["profile"])
        assertEquals(Document(mapOf("itemId" to 2001, "count" to 7)), document.get("bag", Document::class.java)["potion"])
        assertEquals(false, document.get("bag", Document::class.java).containsKey("sword"))
        assertEquals(listOf(Document(mapOf("questId" to 10, "status" to "done"))), document["quests"])
        assertEquals(listOf("vip", "returning"), document["tags"])
    }

    @Test
    fun `tracked wrapper delete removes Mongo document`() = withDatabase { database ->
        val runtime = runtimeFor(1, database)
        val player = TrackedPlayer(
            runtime.context(),
            PlayerEntity(
                id = 1,
                name = "alice",
                profile = Profile("alice", 1),
                bag = linkedMapOf("sword" to ItemStack(1001, 1)),
                quests = mutableListOf(),
                tags = linkedSetOf("newbie"),
            ),
        )

        runtime.enqueueCreated(player)
        assertTrue(runtime.flushSafely())
        runtime.enqueueDelete()
        assertTrue(runtime.flushSafely())

        assertTrue(database.getCollection<Document>(COLLECTION).find(Document()).toList().isEmpty())
    }

    private fun runtimeFor(id: Int, database: MongoDatabase): MongoTrackedDocumentRuntime {
        return MongoTrackedDocumentRuntime(COLLECTION, id, database)
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
        return assertNotNull(database.getCollection<Document>(COLLECTION).find(eq("_id", id)).firstOrNull())
    }

    private data class PlayerEntity(
        override val id: Int,
        val name: String,
        val profile: Profile,
        val bag: MutableMap<String, ItemStack>,
        val quests: MutableList<QuestState>,
        val tags: MutableSet<String>,
    ) : Entity<Int>

    private data class Profile(
        val nickname: String,
        val avatar: Int,
    )

    private data class ItemStack(
        val itemId: Int,
        val count: Int,
    )

    private data class QuestState(
        val questId: Int,
        val status: String,
    )

    private class TrackedPlayer(
        private val ctx: MongoTrackContext,
        entity: PlayerEntity,
    ) : MongoTrackedObjectSupport(ctx.queue), MongoTrackedDocument<Int, PlayerEntity> {
        override val id: Int = entity.id
        var name: String by ctx.trackedValue("name", entity.name)
        val profile: TrackedProfile = trackChild(TrackedProfile(ctx.path("profile"), entity.profile, ctx.queue))
        val bag: MutableMap<String, TrackedItemStack> by mongoTrackedMap(
            path = ctx.path("bag"),
            initialValue = entity.bag.mapValues { (key, value) ->
                trackChild(TrackedItemStack(ctx.path("bag").child(key), value, ctx.queue))
            }.toMutableMap(),
            queue = ctx.queue,
            persistentValue = { value -> value.toMongoValue() },
        )
        val quests: MutableList<TrackedQuestState> by mongoTrackedList(
            path = ctx.path("quests"),
            initialValue = entity.quests.mapIndexed { index, value ->
                trackChild(TrackedQuestState(ctx.path("quests").child(index), value, ctx.queue))
            }.toMutableList(),
            queue = ctx.queue,
            persistentValue = { value -> value.toMongoValue() },
        )
        val tags: MutableSet<String> by mongoTrackedSet(ctx.path("tags"), entity.tags, ctx.queue)

        override fun toEntity(): PlayerEntity {
            return PlayerEntity(
                id = id,
                name = name,
                profile = profile.toEntity(),
                bag = bag.mapValues { (_, value) -> value.toEntity() }.toMutableMap(),
                quests = quests.map { value -> value.toEntity() }.toMutableList(),
                tags = tags.toMutableSet(),
            )
        }

        override fun toMongoValue(): Any? {
            return mapOf(
                "_id" to mongoValueOf(id),
                "name" to mongoValueOf(name),
                "profile" to profile.toMongoValue(),
                "bag" to mongoValueOf(bag),
                "quests" to mongoValueOf(quests),
                "tags" to mongoValueOf(tags),
            )
        }
    }

    private class TrackedProfile(
        private val path: MongoPath,
        entity: Profile,
        queue: MongoChangeQueue,
    ) : MongoTrackedObjectSupport(queue) {
        var nickname: String by mongoTrackedValue(path.child("nickname"), entity.nickname, queue, ::currentDirtyTarget)
        var avatar: Int by mongoTrackedValue(path.child("avatar"), entity.avatar, queue, ::currentDirtyTarget)

        fun toEntity(): Profile = Profile(nickname, avatar)

        override fun toMongoValue(): Any? {
            return Document(mapOf("nickname" to mongoValueOf(nickname), "avatar" to mongoValueOf(avatar)))
        }
    }

    private class TrackedItemStack(
        private val path: MongoPath,
        entity: ItemStack,
        queue: MongoChangeQueue,
    ) : MongoTrackedObjectSupport(queue) {
        var itemId: Int by mongoTrackedValue(path.child("itemId"), entity.itemId, queue, ::currentDirtyTarget)
        var count: Int by mongoTrackedValue(path.child("count"), entity.count, queue, ::currentDirtyTarget)

        fun toEntity(): ItemStack = ItemStack(itemId, count)

        override fun toMongoValue(): Any? {
            return Document(mapOf("itemId" to mongoValueOf(itemId), "count" to mongoValueOf(count)))
        }
    }

    private class TrackedQuestState(
        private val path: MongoPath,
        entity: QuestState,
        queue: MongoChangeQueue,
    ) : MongoTrackedObjectSupport(queue) {
        var questId: Int by mongoTrackedValue(path.child("questId"), entity.questId, queue, ::currentDirtyTarget)
        var status: String by mongoTrackedValue(path.child("status"), entity.status, queue, ::currentDirtyTarget)

        fun toEntity(): QuestState = QuestState(questId, status)

        override fun toMongoValue(): Any? {
            return Document(mapOf("questId" to mongoValueOf(questId), "status" to mongoValueOf(status)))
        }
    }

    private companion object {
        const val COLLECTION = "tracked_players"

        private var mongoContainer: MongoDBContainer? = null

        fun mongo(): MongoDBContainer {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available")
            return mongoContainer ?: MongoDBContainer(DockerImageName.parse("mongo:7.0.14"))
                .also { container ->
                    container.start()
                    mongoContainer = container
                }
        }
    }
}
