package io.github.mikai233.asteria.persistence.mongodb.ksp

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlin.test.Test
import kotlin.test.assertContains

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
                    MongoEntityPropertyModel("bag", "bag", MUTABLE_MAP.parameterizedBy(STRING, ITEM_STACK), MongoEntityPropertyKind.Map),
                    MongoEntityPropertyModel("quests", "quests", MUTABLE_LIST.parameterizedBy(QUEST_STATE), MongoEntityPropertyKind.List),
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
                ),
            ),
        )

        val code = file.toString()

        assertContains(code, "class TrackedPlayerEntity(")
        assertContains(code, "override val id: Long = entity.id")
        assertContains(code, "var name: String by ctx.trackedValue(\"name\", entity.name)")
        assertContains(code, "var level: Int by ctx.trackedValue(\"lv\", entity.level)")
        assertContains(code, "public val profile: TrackedPlayerProfile =")
        assertContains(code, "trackChild(TrackedPlayerProfile(ctx.path(\"profile\"), entity.profile, ctx.queue, ::currentDirtyTarget))")
        assertContains(code, "public val bag: MutableMap<String, ItemStack> by")
        assertContains(code, "mongoTrackedMap(path = ctx.path(\"bag\")")
        assertContains(code, "public val quests: MutableList<QuestState> by")
        assertContains(code, "mongoTrackedList(path = ctx.path(\"quests\")")
        assertContains(code, "class TrackedPlayerProfile(")
        assertContains(code, "public var nickname: String by")
        assertContains(code, "mongoTrackedValue(path.child(\"nickname\"), entity.nickname, queue, dirtyTarget = ::effectiveDirtyTarget)")
        assertContains(code, "fun toEntity(): Profile")
        assertContains(code, "override fun toEntity(): PlayerEntity")
        assertContains(code, "profile = profile.toEntity()")
        assertContains(code, "bag = bag.toMutableMap()")
        assertContains(code, "quests = quests.toMutableList()")
        assertContains(code, "override fun toMongoValue(): Any?")
        assertContains(code, "\"_id\" to mongoValueOf(id)")
        assertContains(code, "\"lv\" to mongoValueOf(level)")
        assertContains(code, "object PlayerEntityMongo")
        assertContains(code, "const val COLLECTION: String = \"players\"")
        assertContains(code, "fun table(")
        assertContains(code, "MongoKeyedDocumentTable<Long, PlayerEntity, TrackedPlayerEntity>")
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
        val QUEST_STATE = ClassName("com.example.player", "QuestState")
    }
}
