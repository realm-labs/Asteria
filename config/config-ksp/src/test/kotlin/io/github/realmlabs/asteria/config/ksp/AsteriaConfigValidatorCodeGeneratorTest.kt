package io.github.realmlabs.asteria.config.ksp

import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertContains

class AsteriaConfigValidatorCodeGeneratorTest {
    @Test
    fun `generates validator list`() {
        val file = AsteriaConfigValidatorCodeGenerator.buildFile(
            config = ConfigValidatorCodegenConfig(
                packageName = "com.example.generated",
                className = "GeneratedGameConfigValidators",
            ),
            validators = listOf(
                ConfigValidatorModel(ITEM_VALIDATOR, objectDeclaration = true),
                ConfigValidatorModel(SHOP_VALIDATOR, objectDeclaration = false),
            ),
        )

        val code = file.toString()

        assertContains(code, "object GeneratedGameConfigValidators")
        assertContains(code, "val ALL: List<ConfigValidator> = listOf(")
        assertContains(code, "ItemConfigValidator")
        assertContains(code, "ShopConfigValidator()")
    }

    @Test
    fun `splits large validator lists into chunk files`() {
        val files = AsteriaConfigValidatorCodeGenerator.buildFiles(
            config = ConfigValidatorCodegenConfig(
                packageName = "com.example.generated",
                className = "GeneratedGameConfigValidators",
            ),
            validators = (0..200).map { index ->
                ConfigValidatorModel(ClassName("com.example.config", "Validator$index"), objectDeclaration = false)
            },
        )

        val fileNames = files.map { it.fileName }
        val aggregator = files.first { it.fileName == "GeneratedGameConfigValidators" }.file.toString()
        val chunk0 = files.first { it.fileName == "GeneratedGameConfigValidatorsChunk0" }.file.toString()
        val chunk1 = files.first { it.fileName == "GeneratedGameConfigValidatorsChunk1" }.file.toString()

        assertContains(fileNames, "GeneratedGameConfigValidators")
        assertContains(fileNames, "GeneratedGameConfigValidatorsChunk0")
        assertContains(fileNames, "GeneratedGameConfigValidatorsChunk1")
        assertContains(aggregator, "addAll(GeneratedGameConfigValidatorsChunk0.ALL)")
        assertContains(aggregator, "addAll(GeneratedGameConfigValidatorsChunk1.ALL)")
        assertContains(chunk0, "internal object GeneratedGameConfigValidatorsChunk0")
        assertContains(chunk1, "internal object GeneratedGameConfigValidatorsChunk1")
        assertContains(chunk1, "listOf(")
    }

    private companion object {
        val ITEM_VALIDATOR = ClassName("com.example.config", "ItemConfigValidator")
        val SHOP_VALIDATOR = ClassName("com.example.config", "ShopConfigValidator")
    }
}
