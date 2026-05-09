package io.github.realmlabs.asteria.persistence.mongodb.ksp

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class AsteriaMongoEntityCodeGeneratorTest {
    @Test
    fun `generates tracked document wrapper and table helper`() {
        val file = AsteriaMongoEntityCodeGenerator.buildFile(
            MongoEntityCodegenModel(
                packageName = "com.example.player",
                entityType = PLAYER_ENTITY,
                wrapperName = "TrackedPlayerEntity",
                helperName = "PlayerEntityMongo",
                collectionName = "players",
                id = MongoEntityPropertyModel("id", "_id", LONG),
                properties = listOf(
                    MongoEntityPropertyModel("id", "_id", LONG),
                    MongoEntityPropertyModel("name", "name", STRING),
                    MongoEntityPropertyModel("level", "lv", INT),
                    MongoEntityPropertyModel(
                        "profile",
                        "profile",
                        PROFILE,
                        MongoEntityPropertyKind.Object,
                        TRACKED_PROFILE,
                    ),
                    MongoEntityPropertyModel(
                        "bag",
                        "bag",
                        MUTABLE_MAP.parameterizedBy(STRING, ITEM_STACK),
                        MongoEntityPropertyKind.Map,
                        MUTABLE_MAP.parameterizedBy(STRING, TRACKED_ITEM_STACK),
                        MongoEntityPropertyKind.Object,
                    ),
                    MongoEntityPropertyModel(
                        "quests",
                        "quests",
                        MUTABLE_LIST.parameterizedBy(QUEST_STATE),
                        MongoEntityPropertyKind.List,
                        MUTABLE_LIST.parameterizedBy(TRACKED_QUEST_STATE),
                        MongoEntityPropertyKind.Object,
                    ),
                ),
                nestedObjects = listOf(
                    MongoNestedObjectModel(
                        sourceType = PROFILE,
                        wrapperType = TRACKED_PROFILE,
                        properties = listOf(
                            MongoEntityPropertyModel("nickname", "nickname", STRING),
                            MongoEntityPropertyModel("avatar", "avatar", INT),
                        ),
                    ),
                    MongoNestedObjectModel(
                        sourceType = ITEM_STACK,
                        wrapperType = TRACKED_ITEM_STACK,
                        properties = listOf(
                            MongoEntityPropertyModel("itemId", "itemId", INT),
                            MongoEntityPropertyModel("count", "count", INT),
                        ),
                    ),
                    MongoNestedObjectModel(
                        sourceType = QUEST_STATE,
                        wrapperType = TRACKED_QUEST_STATE,
                        properties = listOf(
                            MongoEntityPropertyModel("questId", "questId", INT),
                            MongoEntityPropertyModel("status", "status", INT),
                        ),
                    ),
                ),
            ),
        )

        val code = file.toString()

        assertContains(code, "class TrackedPlayerEntity(")
        assertContains(code, "override val id: Long = entity.id")
        assertContains(code, "var name: String by ctx.trackedValue(\"name\", entity.name)")
        assertContains(code, "var level: Int by ctx.trackedValue(\"lv\", entity.level)")
        assertContains(code, "public val profile: TrackedPlayerProfile =")
        assertContains(
            code,
            "trackChild(TrackedPlayerProfile(ctx.path(\"profile\"), entity.profile, ctx.queue, ::currentDirtyTarget))"
        )
        assertContains(code, "public val bag: TrackedPlayerEntityBagMap =")
        assertContains(
            code,
            "trackChild(TrackedPlayerEntityBagMap(ctx.path(\"bag\"), entity.bag, ctx.queue, ::currentDirtyTarget))"
        )
        assertContains(
            code,
            "public val quests: TrackedPlayerEntityQuestsList ="
        )
        assertContains(
            code,
            "trackChild(TrackedPlayerEntityQuestsList(ctx.path(\"quests\"), entity.quests, ctx.queue, ::currentDirtyTarget))"
        )
        assertContains(code, "class TrackedPlayerProfile(")
        assertContains(code, "class TrackedItemStack(")
        assertContains(code, "class TrackedQuestState(")
        assertContains(code, "private val dirtyTargetProvider: () -> MongoDirtyTarget? = { null },")
        assertContains(code, "public var nickname: String by")
        assertContains(
            code,
            "mongoTrackedValue(path.child(\"nickname\"), entity.nickname, queue, dirtyTarget = ::effectiveDirtyTarget)"
        )
        assertContains(code, "fun toEntity(): Profile")
        assertContains(code, "override fun toEntity(): PlayerEntity")
        assertContains(code, "profile = profile.toEntity()")
        assertContains(code, "bag = bag.toEntityMap()")
        assertContains(code, "quests = quests.toEntityList()")
        assertContains(code, "override fun toMongoValue(): Any?")
        assertContains(code, "\"_id\" to mongoValueOf(id)")
        assertContains(code, "\"lv\" to mongoValueOf(level)")
        assertContains(code, "object PlayerEntityMongo")
        assertContains(code, "const val COLLECTION: String = \"players\"")
        assertContains(code, "class TrackedPlayerEntityBagMap(")
        assertContains(code, ") : MongoTrackedObjectSupport(queue),\n    MutableMap<String, TrackedItemStack> {")
        assertContains(code, "private val backing: MongoTrackedMutableMap<String, TrackedItemStack> =")
        assertContains(
            code,
            "initialValue = initialValue.mapValues { (key, value) -> trackEntity(key, value) }.toMutableMap(),"
        )
        assertContains(code, "trackedValue = { _, value -> trackChild(value) },")
        assertContains(code, "public operator fun `set`(key: String, `value`: ItemStack)")
        assertContains(code, "backing[key] = trackEntity(key, value)")
        assertContains(code, "public fun toEntityMap(): MutableMap<String, ItemStack> =")
        assertContains(
            code,
            "private fun trackEntity(key: String, `value`: ItemStack): TrackedItemStack = TrackedItemStack(path.child(key), value, queue, ::effectiveDirtyTarget)"
        )
        assertContains(code, "class TrackedPlayerEntityQuestsList(")
        assertContains(code, ") : MongoTrackedObjectSupport(queue),\n    MutableList<TrackedQuestState> {")
        assertContains(code, "private var wholeListDirty: Boolean = false")
        assertContains(code, "private val backing: MongoTrackedMutableList<TrackedQuestState> =")
        assertContains(
            code,
            "initialValue = initialValue.mapIndexed { index, value -> trackEntity(index, value) }.toMutableList(),"
        )
        assertContains(code, "public operator fun `set`(index: Int, `value`: QuestState)")
        assertContains(code, "public fun add(`value`: QuestState): Boolean")
        assertContains(code, "public fun add(index: Int, `value`: QuestState)")
        assertContains(code, "public fun toEntityList(): MutableList<QuestState> =")
        assertContains(code, "private fun markWholeListDirty()")
        assertContains(
            code,
            "if (wholeListDirty) MongoDirtyTarget(path, this) else null"
        )
        assertContains(code, "public val SCAN_PLAN: EntityScanPlan<PlayerEntity> = mongoScanPlan(")
        assertContains(code, "mongoScannedField(\"name\") { entity: PlayerEntity -> entity.name }")
        assertContains(code, "mongoScannedField(\"lv\") { entity: PlayerEntity -> entity.level }")
        assertContains(
            code,
            "mongoScannedMapField(\"bag\") { entity: PlayerEntity -> entity.bag.mapValues { (_, value) -> trackedItemStackMongoValue(value) } }"
        )
        assertContains(code, "trackedPlayerProfileMongoValue(entity.profile)")
        assertContains(
            code,
            "mongoScannedField(\"quests\") { entity: PlayerEntity -> entity.quests.map { value -> trackedQuestStateMongoValue(value) } }"
        )
        assertContains(code, "fun table(")
        assertContains(code, "MongoKeyedDocumentTable<Long, PlayerEntity, TrackedPlayerEntity>")
        assertContains(code, "fun scannedTable(")
        assertContains(code, "MongoScannedKeyedDocumentTable<Long, PlayerEntity>")
        assertContains(code, "metrics: Metrics = NoopMetrics")
    }

    @Test
    fun `rejects nullable collection codegen models`() {
        val error = assertFailsWith<IllegalArgumentException> {
            AsteriaMongoEntityCodeGenerator.buildFile(
                MongoEntityCodegenModel(
                    packageName = "com.example.player",
                    entityType = PLAYER_ENTITY,
                    wrapperName = "TrackedPlayerEntity",
                    helperName = "PlayerEntityMongo",
                    collectionName = "players",
                    id = MongoEntityPropertyModel("id", "_id", LONG),
                    properties = listOf(
                        MongoEntityPropertyModel("id", "_id", LONG),
                        MongoEntityPropertyModel(
                            "bag",
                            "bag",
                            MUTABLE_MAP.parameterizedBy(STRING, ITEM_STACK).copy(nullable = true),
                        ),
                    ),
                ),
            )
        }

        assertContains(error.message.orEmpty(), "Nullable Mongo collection properties are not supported")
    }

    @Test
    fun `rejects nullable collection inside nested object codegen models`() {
        val error = assertFailsWith<IllegalArgumentException> {
            AsteriaMongoEntityCodeGenerator.buildFile(
                MongoEntityCodegenModel(
                    packageName = "com.example.player",
                    entityType = PLAYER_ENTITY,
                    wrapperName = "TrackedPlayerEntity",
                    helperName = "PlayerEntityMongo",
                    collectionName = "players",
                    id = MongoEntityPropertyModel("id", "_id", LONG),
                    properties = listOf(
                        MongoEntityPropertyModel("id", "_id", LONG),
                        MongoEntityPropertyModel(
                            "profile",
                            "profile",
                            PROFILE,
                            MongoEntityPropertyKind.Object,
                            TRACKED_PROFILE,
                        ),
                    ),
                    nestedObjects = listOf(
                        MongoNestedObjectModel(
                            sourceType = PROFILE,
                            wrapperType = TRACKED_PROFILE,
                            properties = listOf(
                                MongoEntityPropertyModel(
                                    "tags",
                                    "tags",
                                    MUTABLE_LIST.parameterizedBy(STRING).copy(nullable = true),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertContains(error.message.orEmpty(), "Nullable Mongo collection properties are not supported")
    }

    private companion object {
        val INT = ClassName("kotlin", "Int")
        val LONG = ClassName("kotlin", "Long")
        val STRING = ClassName("kotlin", "String")
        val MUTABLE_MAP = ClassName("kotlin.collections", "MutableMap")
        val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
        val PLAYER_ENTITY = ClassName("com.example.player", "PlayerEntity")
        val PROFILE = ClassName("com.example.player", "Profile")
        val TRACKED_PROFILE = ClassName("com.example.player", "TrackedPlayerProfile")
        val ITEM_STACK = ClassName("com.example.player", "ItemStack")
        val TRACKED_ITEM_STACK = ClassName("com.example.player", "TrackedItemStack")
        val QUEST_STATE = ClassName("com.example.player", "QuestState")
        val TRACKED_QUEST_STATE = ClassName("com.example.player", "TrackedQuestState")
    }
}
