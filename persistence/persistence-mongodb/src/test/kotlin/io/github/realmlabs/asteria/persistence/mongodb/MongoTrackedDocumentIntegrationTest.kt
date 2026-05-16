package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.persistence.Entity
import io.github.realmlabs.asteria.persistence.mongodb.common.MongoPath
import io.github.realmlabs.asteria.persistence.mongodb.common.mongoValueOf
import io.github.realmlabs.asteria.persistence.mongodb.scanned.MongoScannedDocumentRuntime
import io.github.realmlabs.asteria.persistence.mongodb.scanned.mongoScanPlan
import io.github.realmlabs.asteria.persistence.mongodb.scanned.mongoScannedField
import io.github.realmlabs.asteria.persistence.mongodb.scanned.mongoScannedMapField
import io.github.realmlabs.asteria.persistence.mongodb.tracked.*
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoChangeQueue
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoPendingWriteQueue
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.util.*
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
        player.bag["shield"] = ItemStack(3001, 1)
        player.bag["shield"]!!.count = 2
        player.quests[0].status = "done"
        player.quests.add(QuestState(20, "open"))
        player.quests[1].status = "done"
        player.tags.remove("newbie")
        player.tags.add("returning")
        assertTrue(runtime.flushSafely())

        val document = findPlayer(database, 1)
        assertEquals("bob", document.getString("name"))
        assertEquals(Document(mapOf("nickname" to "bobby", "avatar" to 1)), document["profile"])
        assertEquals(
            Document(mapOf("itemId" to 2001, "count" to 7)),
            document.get("bag", Document::class.java)["potion"]
        )
        assertEquals(
            Document(mapOf("itemId" to 3001, "count" to 2)),
            document.get("bag", Document::class.java)["shield"]
        )
        assertEquals(false, document.get("bag", Document::class.java).containsKey("sword"))
        assertEquals(
            listOf(
                Document(mapOf("questId" to 10, "status" to "done")),
                Document(mapOf("questId" to 20, "status" to "done")),
            ),
            document["quests"],
        )
        assertEquals(listOf("vip", "returning"), document["tags"])
    }

    @Test
    fun `tracked and scanned runtimes persist the same complex document changes`() = withDatabase { database ->
        val trackedRuntime = runtimeFor(1, database)
        val trackedPlayer = TrackedPlayer(trackedRuntime.context(), initialPlayer(id = 1))
        val scannedRuntime = scannedRuntimeFor(2, database)
        val scannedPlayer = initialScannedPlayer(id = 2)

        trackedRuntime.enqueueCreated(trackedPlayer)
        scannedRuntime.enqueueCreated(scannedPlayer)
        assertTrue(trackedRuntime.flushSafely())
        assertTrue(scannedRuntime.flushSafely())

        applyComplexChanges(trackedPlayer)
        applyComplexChanges(scannedPlayer)
        scannedRuntime.scan(scannedPlayer)
        assertTrue(trackedRuntime.flushSafely())
        assertTrue(scannedRuntime.flushSafely())

        val trackedDocument = withoutId(findPlayer(database, 1))
        val scannedDocument = withoutId(findPlayer(database, 2))
        assertEquals(trackedDocument, scannedDocument)
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

    @Test
    fun `tracked map facade handles raw writes and mutable view removals`() {
        val queue = MongoPendingWriteQueue()
        val player = trackedPlayer(
            queue,
            bag = linkedMapOf(
                "sword.1" to ItemStack(1001, 1),
                "%gem" to ItemStack(2001, 2),
                "" to ItemStack(3001, 3),
            ),
        )

        player.bag["shield.2"] = ItemStack(4001, 4)
        player.bag.getValue("shield.2").count = 5
        player.bag.entries.first { (key) -> key == "sword.1" }
            .setValue(TrackedItemStack(MongoPath(COLLECTION, 1, "bag").child("sword.1"), ItemStack(1001, 9), queue))
        player.bag.keys.remove("%gem")
        player.bag.values.remove(player.bag.getValue(""))

        val write = queue.drain().single()
        assertEquals(
            mapOf(
                "bag.shield%2E2" to Document(mapOf("itemId" to 4001, "count" to 5)),
                "bag.sword%2E1" to Document(mapOf("itemId" to 1001, "count" to 9)),
            ),
            write.sets,
        )
        assertEquals(setOf("bag.%25gem", "bag.%EMPTY"), write.unsets)
    }

    @Test
    fun `tracked list facade supports raw writes and whole-list dirty after structural changes`() {
        val queue = MongoPendingWriteQueue()
        val player = trackedPlayer(
            queue,
            quests = mutableListOf(
                QuestState(10, "open"),
                QuestState(20, "open"),
                QuestState(30, "open"),
            ),
        )

        player.quests[1].status = "active"
        queue.drain().single().also { write ->
            assertEquals(mapOf("quests.1.status" to "active"), write.sets)
        }

        player.quests[1] = QuestState(22, "replaced")
        queue.drain().single().also { write ->
            assertEquals(mapOf("quests.1" to Document(mapOf("questId" to 22, "status" to "replaced"))), write.sets)
        }

        player.quests.add(1, QuestState(15, "inserted"))
        player.quests[2].status = "done-after-insert"
        queue.drain().single().also { write ->
            assertEquals(
                mapOf(
                    "quests" to listOf(
                        Document(mapOf("questId" to 10, "status" to "open")),
                        Document(mapOf("questId" to 15, "status" to "inserted")),
                        Document(mapOf("questId" to 22, "status" to "done-after-insert")),
                        Document(mapOf("questId" to 30, "status" to "open")),
                    ),
                ),
                write.sets,
            )
        }

        val iterator = player.quests.listIterator(1)
        iterator.next()
        iterator.remove()
        player.quests[1].status = "done-after-iterator-remove"
        queue.drain().single().also { write ->
            assertEquals(
                mapOf(
                    "quests" to listOf(
                        Document(mapOf("questId" to 10, "status" to "open")),
                        Document(mapOf("questId" to 22, "status" to "done-after-iterator-remove")),
                        Document(mapOf("questId" to 30, "status" to "open")),
                    ),
                ),
                write.sets,
            )
        }

        player.quests.subList(1, 3).removeAt(1)
        player.quests[1].status = "done-after-sublist-remove"
        queue.drain().single().also { write ->
            assertEquals(
                mapOf(
                    "quests" to listOf(
                        Document(mapOf("questId" to 10, "status" to "open")),
                        Document(mapOf("questId" to 22, "status" to "done-after-sublist-remove")),
                    ),
                ),
                write.sets,
            )
        }
    }

    private fun runtimeFor(id: Int, database: MongoDatabase): MongoTrackedDocumentRuntime {
        return MongoTrackedDocumentRuntime(COLLECTION, id, database)
    }

    private fun scannedRuntimeFor(
        id: Int,
        database: MongoDatabase,
    ): MongoScannedDocumentRuntime<Int, ScannedPlayerEntity> {
        return MongoScannedDocumentRuntime(
            collectionName = COLLECTION,
            documentId = id,
            scanPlan = mongoScanPlan(
                mongoScannedField("name") { entity -> entity.name },
                mongoScannedField("profile") { entity -> entity.profile.toDocument() },
                mongoScannedMapField("bag") { entity ->
                    entity.bag.mapValues { (_, value) -> value.toDocument() }
                },
                mongoScannedField("quests") { entity ->
                    entity.quests.map { value -> value.toDocument() }
                },
                mongoScannedField("tags") { entity -> entity.tags.toList() },
            ),
            database = database,
        )
    }

    private fun trackedPlayer(
        queue: MongoPendingWriteQueue,
        bag: MutableMap<String, ItemStack> = linkedMapOf("sword" to ItemStack(1001, 1)),
        quests: MutableList<QuestState> = mutableListOf(QuestState(10, "open")),
    ): TrackedPlayer {
        return TrackedPlayer(
            MongoTrackContext(COLLECTION, 1, queue),
            PlayerEntity(
                id = 1,
                name = "alice",
                profile = Profile("alice", 1),
                bag = bag,
                quests = quests,
                tags = linkedSetOf("newbie"),
            ),
        )
    }

    private fun initialPlayer(id: Int): PlayerEntity {
        return PlayerEntity(
            id = id,
            name = "alice",
            profile = Profile("alice", 1),
            bag = linkedMapOf("sword" to ItemStack(1001, 1), "potion" to ItemStack(2001, 2)),
            quests = mutableListOf(QuestState(10, "open")),
            tags = linkedSetOf("newbie", "vip"),
        )
    }

    private fun initialScannedPlayer(id: Int): ScannedPlayerEntity {
        return ScannedPlayerEntity(
            id = id,
            name = "alice",
            profile = Profile("alice", 1),
            bag = linkedMapOf("sword" to ItemStack(1001, 1), "potion" to ItemStack(2001, 2)),
            quests = mutableListOf(QuestState(10, "open")),
            tags = linkedSetOf("newbie", "vip"),
        )
    }

    private fun applyComplexChanges(player: TrackedPlayer) {
        player.name = "bob"
        player.profile.nickname = "bobby"
        player.bag.getValue("potion").count = 7
        player.bag.remove("sword")
        player.bag["shield"] = ItemStack(3001, 1)
        player.bag["shield"]!!.count = 2
        player.quests[0].status = "done"
        player.quests.add(QuestState(20, "open"))
        player.quests[1].status = "done"
        player.tags.remove("newbie")
        player.tags.add("returning")
    }

    private fun applyComplexChanges(player: ScannedPlayerEntity) {
        player.name = "bob"
        player.profile = player.profile.copy(nickname = "bobby")
        player.bag["potion"] = player.bag.getValue("potion").copy(count = 7)
        player.bag.remove("sword")
        player.bag["shield"] = ItemStack(3001, 2)
        player.quests[0] = player.quests[0].copy(status = "done")
        player.quests.add(QuestState(20, "open"))
        player.quests[1] = player.quests[1].copy(status = "done")
        player.tags.remove("newbie")
        player.tags.add("returning")
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

    private fun withoutId(document: Document): Document {
        return Document(document).apply { remove("_id") }
    }

    private fun Profile.toDocument(): Document {
        return Document(mapOf("nickname" to nickname, "avatar" to avatar))
    }

    private fun ItemStack.toDocument(): Document {
        return Document(mapOf("itemId" to itemId, "count" to count))
    }

    private fun QuestState.toDocument(): Document {
        return Document(mapOf("questId" to questId, "status" to status))
    }

    private data class PlayerEntity(
        override val id: Int,
        val name: String,
        val profile: Profile,
        val bag: MutableMap<String, ItemStack>,
        val quests: MutableList<QuestState>,
        val tags: MutableSet<String>,
    ) : Entity<Int>

    private data class ScannedPlayerEntity(
        override val id: Int,
        var name: String,
        var profile: Profile,
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
        ctx: MongoTrackContext,
        entity: PlayerEntity,
    ) : MongoTrackedObjectSupport(ctx.queue), MongoTrackedDocument<Int, PlayerEntity> {
        override val id: Int = entity.id
        var name: String by ctx.trackedValue("name", entity.name)
        val profile: TrackedProfile = trackChild(TrackedProfile(ctx.path("profile"), entity.profile, ctx.queue))
        val bag: TrackedPlayerBagMap = trackChild(
            TrackedPlayerBagMap(ctx.path("bag"), entity.bag, ctx.queue, ::currentDirtyTarget),
        )
        val quests: TrackedPlayerQuestsList = trackChild(
            TrackedPlayerQuestsList(ctx.path("quests"), entity.quests, ctx.queue, ::currentDirtyTarget),
        )
        val tags: MutableSet<String> by mongoTrackedSet(ctx.path("tags"), entity.tags, ctx.queue)

        override fun toEntity(): PlayerEntity {
            return PlayerEntity(
                id = id,
                name = name,
                profile = profile.toEntity(),
                bag = bag.toEntityMap(),
                quests = quests.toEntityList(),
                tags = tags.toMutableSet(),
            )
        }

        override fun toMongoValue(): Any {
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

    private class TrackedPlayerBagMap(
        private val path: MongoPath,
        initialValue: MutableMap<String, ItemStack>,
        private val queue: MongoChangeQueue,
        private val dirtyTargetProvider: () -> MongoDirtyTarget? = { null },
    ) : MongoTrackedObjectSupport(queue), MutableMap<String, TrackedItemStack> {
        private val backing: MongoTrackedMutableMap<String, TrackedItemStack> = MongoTrackedMutableMap(
            path = path,
            initialValue = initialValue.mapValues { (key, value) -> trackEntity(key, value) }.toMutableMap(),
            queue = queue,
            persistentValue = { value -> value.toMongoValue() },
            trackedValue = { _, value -> trackChild(value) },
            dirtyTargetProvider = ::effectiveDirtyTarget,
        )

        override val entries: MutableSet<MutableMap.MutableEntry<String, TrackedItemStack>>
            get() = backing.entries

        override val keys: MutableSet<String>
            get() = backing.keys

        override val values: MutableCollection<TrackedItemStack>
            get() = backing.values

        override val size: Int
            get() = backing.size

        override fun containsKey(key: String): Boolean = backing.containsKey(key)

        override fun containsValue(value: TrackedItemStack): Boolean = backing.containsValue(value)

        override fun get(key: String): TrackedItemStack? = backing[key]

        override fun isEmpty(): Boolean = backing.isEmpty()

        override fun clear() {
            backing.clear()
        }

        override fun put(key: String, value: TrackedItemStack): TrackedItemStack? = backing.put(key, value)

        override fun putAll(from: Map<out String, TrackedItemStack>) {
            backing.putAll(from)
        }

        override fun remove(key: String): TrackedItemStack? = backing.remove(key)

        operator fun set(key: String, value: ItemStack) {
            backing[key] = trackEntity(key, value)
        }

        fun toEntityMap(): MutableMap<String, ItemStack> {
            return backing.mapValues { (_, value) -> value.toEntity() }.toMutableMap()
        }

        override fun toMongoValue(): Any? = backing.toMongoValue()

        private fun trackEntity(key: String, value: ItemStack): TrackedItemStack {
            return TrackedItemStack(path.child(key), value, queue, ::effectiveDirtyTarget)
        }

        private fun effectiveDirtyTarget(): MongoDirtyTarget? = dirtyTargetProvider() ?: currentDirtyTarget()
    }

    private class TrackedPlayerQuestsList(
        private val path: MongoPath,
        initialValue: List<QuestState>,
        private val queue: MongoChangeQueue,
        private val dirtyTargetProvider: () -> MongoDirtyTarget? = { null },
    ) : MongoTrackedObjectSupport(queue), MutableList<TrackedQuestState> {
        private var wholeListDirty: Boolean = false

        private val backing: MongoTrackedMutableList<TrackedQuestState> = MongoTrackedMutableList(
            path = path,
            initialValue = initialValue.mapIndexed { index, value -> trackEntity(index, value) }.toMutableList(),
            queue = queue,
            persistentValue = { value -> value.toMongoValue() },
            trackedValue = { _, value -> trackChild(value) },
            dirtyTargetProvider = ::effectiveDirtyTarget,
        )

        override val size: Int
            get() = backing.size

        override fun contains(element: TrackedQuestState): Boolean = backing.contains(element)

        override fun containsAll(elements: Collection<TrackedQuestState>): Boolean = backing.containsAll(elements)

        override fun get(index: Int): TrackedQuestState = backing[index]

        override fun indexOf(element: TrackedQuestState): Int = backing.indexOf(element)

        override fun isEmpty(): Boolean = backing.isEmpty()

        override fun iterator(): MutableIterator<TrackedQuestState> = listIterator()

        override fun lastIndexOf(element: TrackedQuestState): Int = backing.lastIndexOf(element)

        override fun listIterator(): MutableListIterator<TrackedQuestState> = listIterator(0)

        override fun listIterator(index: Int): MutableListIterator<TrackedQuestState> {
            val iterator = backing.listIterator(index)
            return object : MutableListIterator<TrackedQuestState> {
                override fun add(element: TrackedQuestState) {
                    iterator.add(element)
                    markWholeListDirty()
                }

                override fun hasNext(): Boolean = iterator.hasNext()

                override fun hasPrevious(): Boolean = iterator.hasPrevious()

                override fun next(): TrackedQuestState = iterator.next()

                override fun nextIndex(): Int = iterator.nextIndex()

                override fun previous(): TrackedQuestState = iterator.previous()

                override fun previousIndex(): Int = iterator.previousIndex()

                override fun remove() {
                    iterator.remove()
                    markWholeListDirty()
                }

                override fun set(element: TrackedQuestState) {
                    iterator.set(element)
                }
            }
        }

        override fun subList(fromIndex: Int, toIndex: Int): MutableList<TrackedQuestState> {
            var endExclusive = toIndex
            return object : AbstractMutableList<TrackedQuestState>() {
                override val size: Int
                    get() = endExclusive - fromIndex

                override fun get(index: Int): TrackedQuestState = this@TrackedPlayerQuestsList[fromIndex + index]

                override fun set(index: Int, element: TrackedQuestState): TrackedQuestState {
                    return this@TrackedPlayerQuestsList.set(fromIndex + index, element)
                }

                override fun add(index: Int, element: TrackedQuestState) {
                    this@TrackedPlayerQuestsList.add(fromIndex + index, element)
                    endExclusive++
                }

                override fun removeAt(index: Int): TrackedQuestState {
                    val removed = this@TrackedPlayerQuestsList.removeAt(fromIndex + index)
                    endExclusive--
                    return removed
                }
            }
        }

        override fun add(element: TrackedQuestState): Boolean {
            val added = backing.add(element)
            if (added) markWholeListDirty()
            return added
        }

        override fun add(index: Int, element: TrackedQuestState) {
            backing.add(index, element)
            markWholeListDirty()
        }

        override fun addAll(elements: Collection<TrackedQuestState>): Boolean {
            val added = backing.addAll(elements)
            if (added) markWholeListDirty()
            return added
        }

        override fun addAll(index: Int, elements: Collection<TrackedQuestState>): Boolean {
            val added = backing.addAll(index, elements)
            if (added) markWholeListDirty()
            return added
        }

        override fun clear() {
            if (backing.isEmpty()) return
            backing.clear()
            markWholeListDirty()
        }

        override fun remove(element: TrackedQuestState): Boolean {
            val removed = backing.remove(element)
            if (removed) markWholeListDirty()
            return removed
        }

        override fun removeAll(elements: Collection<TrackedQuestState>): Boolean {
            val removed = backing.removeAll(elements)
            if (removed) markWholeListDirty()
            return removed
        }

        override fun removeAt(index: Int): TrackedQuestState {
            val removed = backing.removeAt(index)
            markWholeListDirty()
            return removed
        }

        override fun retainAll(elements: Collection<TrackedQuestState>): Boolean {
            val changed = backing.retainAll(elements)
            if (changed) markWholeListDirty()
            return changed
        }

        override fun set(index: Int, element: TrackedQuestState): TrackedQuestState = backing.set(index, element)

        operator fun set(index: Int, value: QuestState) {
            backing[index] = trackEntity(index, value)
        }

        fun add(value: QuestState): Boolean {
            backing.add(trackEntity(backing.size, value))
            markWholeListDirty()
            return true
        }

        fun add(index: Int, value: QuestState) {
            backing.add(index, trackEntity(index, value))
            markWholeListDirty()
        }

        fun toEntityList(): MutableList<QuestState> {
            return backing.map { value -> value.toEntity() }.toMutableList()
        }

        override fun toMongoValue(): Any? = backing.toMongoValue()

        private fun trackEntity(index: Int, value: QuestState): TrackedQuestState {
            return TrackedQuestState(path.child(index), value, queue, ::effectiveDirtyTarget)
        }

        private fun markWholeListDirty() {
            wholeListDirty = true
        }

        private fun effectiveDirtyTarget(): MongoDirtyTarget? {
            return dirtyTargetProvider() ?: currentDirtyTarget() ?: if (wholeListDirty) {
                MongoDirtyTarget(path, this)
            } else {
                null
            }
        }
    }

    private class TrackedProfile(
        path: MongoPath,
        entity: Profile,
        queue: MongoChangeQueue,
    ) : MongoTrackedObjectSupport(queue) {
        var nickname: String by mongoTrackedValue(path.child("nickname"), entity.nickname, queue, ::currentDirtyTarget)
        var avatar: Int by mongoTrackedValue(path.child("avatar"), entity.avatar, queue, ::currentDirtyTarget)

        fun toEntity(): Profile = Profile(nickname, avatar)

        override fun toMongoValue(): Any {
            return Document(mapOf("nickname" to mongoValueOf(nickname), "avatar" to mongoValueOf(avatar)))
        }
    }

    private class TrackedItemStack(
        path: MongoPath,
        entity: ItemStack,
        queue: MongoChangeQueue,
        private val dirtyTargetProvider: () -> MongoDirtyTarget? = { null },
    ) : MongoTrackedObjectSupport(queue) {
        var itemId: Int by mongoTrackedValue(path.child("itemId"), entity.itemId, queue, ::effectiveDirtyTarget)
        var count: Int by mongoTrackedValue(path.child("count"), entity.count, queue, ::effectiveDirtyTarget)

        fun toEntity(): ItemStack = ItemStack(itemId, count)

        override fun toMongoValue(): Any {
            return Document(mapOf("itemId" to mongoValueOf(itemId), "count" to mongoValueOf(count)))
        }

        private fun effectiveDirtyTarget(): MongoDirtyTarget? = dirtyTargetProvider() ?: currentDirtyTarget()
    }

    private class TrackedQuestState(
        path: MongoPath,
        entity: QuestState,
        queue: MongoChangeQueue,
        private val dirtyTargetProvider: () -> MongoDirtyTarget? = { null },
    ) : MongoTrackedObjectSupport(queue) {
        var questId: Int by mongoTrackedValue(path.child("questId"), entity.questId, queue, ::effectiveDirtyTarget)
        var status: String by mongoTrackedValue(path.child("status"), entity.status, queue, ::effectiveDirtyTarget)

        fun toEntity(): QuestState = QuestState(questId, status)

        override fun toMongoValue(): Any {
            return Document(mapOf("questId" to mongoValueOf(questId), "status" to mongoValueOf(status)))
        }

        private fun effectiveDirtyTarget(): MongoDirtyTarget? = dirtyTargetProvider() ?: currentDirtyTarget()
    }

    private companion object {
        const val COLLECTION = "tracked_players"

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
