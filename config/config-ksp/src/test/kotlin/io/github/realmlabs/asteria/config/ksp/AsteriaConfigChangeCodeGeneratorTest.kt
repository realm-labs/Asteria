package io.github.realmlabs.asteria.config.ksp

import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AsteriaConfigChangeCodeGeneratorTest {
    @Test
    fun `generates handler list for receiver type`() {
        val file = AsteriaConfigChangeCodeGenerator.buildFile(
            config = ConfigChangeCodegenConfig(
                packageName = "com.example.generated",
                className = "GeneratedPlayerConfigChangeHandlers",
                receiverType = PLAYER_ACTOR,
            ),
            handlers = listOf(
                ConfigChangeHandlerModel(ACTIVITY_HANDLER),
                ConfigChangeHandlerModel(QUEST_HANDLER),
            ),
        )

        val code = file.toString()

        assertContains(code, "object GeneratedPlayerConfigChangeHandlers")
        assertContains(
            code,
            "val ALL: List<ConfigChangeHandler<PlayerActor>> = listOf(",
        )
        assertContains(code, "ActivityConfigChangeHandler()")
        assertContains(code, "QuestConfigChangeHandler()")
    }

    @Test
    fun `splits large handler lists into chunk files`() {
        val files = AsteriaConfigChangeCodeGenerator.buildFiles(
            config = ConfigChangeCodegenConfig(
                packageName = "com.example.generated",
                className = "GeneratedPlayerConfigChangeHandlers",
                receiverType = PLAYER_ACTOR,
            ),
            handlers = (0..200).map { index ->
                ConfigChangeHandlerModel(ClassName("com.example.player.config", "Handler$index"))
            },
        )

        val fileNames = files.map { it.fileName }
        val aggregator = files.first { it.fileName == "GeneratedPlayerConfigChangeHandlers" }.file.toString()
        val chunk0 = files.first { it.fileName == "GeneratedPlayerConfigChangeHandlersChunk0" }.file.toString()
        val chunk1 = files.first { it.fileName == "GeneratedPlayerConfigChangeHandlersChunk1" }.file.toString()

        assertContains(fileNames, "GeneratedPlayerConfigChangeHandlers")
        assertContains(fileNames, "GeneratedPlayerConfigChangeHandlersChunk0")
        assertContains(fileNames, "GeneratedPlayerConfigChangeHandlersChunk1")
        assertContains(aggregator, "addAll(GeneratedPlayerConfigChangeHandlersChunk0.ALL)")
        assertContains(aggregator, "addAll(GeneratedPlayerConfigChangeHandlersChunk1.ALL)")
        assertContains(chunk0, "internal object GeneratedPlayerConfigChangeHandlersChunk0")
        assertContains(chunk1, "internal object GeneratedPlayerConfigChangeHandlersChunk1")
        assertContains(chunk1, "listOf(")
    }

    @Test
    fun `uses single handler file when handler count drops below chunk threshold`() {
        val files = AsteriaConfigChangeCodeGenerator.buildFiles(
            config = ConfigChangeCodegenConfig(
                packageName = "com.example.generated",
                className = "GeneratedPlayerConfigChangeHandlers",
                receiverType = PLAYER_ACTOR,
            ),
            handlers = listOf(ConfigChangeHandlerModel(ACTIVITY_HANDLER)),
        )

        val fileNames = files.map { it.fileName }
        val main = files.single { it.fileName == "GeneratedPlayerConfigChangeHandlers" }.file.toString()

        assertFalse(fileNames.any { it.startsWith("GeneratedPlayerConfigChangeHandlersChunk") })
        assertContains(main, "object GeneratedPlayerConfigChangeHandlers")
        assertContains(main, "ActivityConfigChangeHandler()")
    }

    private companion object {
        val PLAYER_ACTOR = ClassName("com.example.player", "PlayerActor")
        val ACTIVITY_HANDLER = ClassName("com.example.player.config", "ActivityConfigChangeHandler")
        val QUEST_HANDLER = ClassName("com.example.player.config", "QuestConfigChangeHandler")
    }
}
