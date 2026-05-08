package io.github.realmlabs.asteria.message.ksp

import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AsteriaMessageCatalogCodeGeneratorTest {
    @Test
    fun `splits large message catalogs into chunk files`() {
        val files = AsteriaMessageCatalogCodeGenerator.buildFiles(
            generatedPackage = "com.example.generated",
            typeNamePart = "Gate",
            bindings = (0..200).map { index ->
                MessageCatalogBindingModel(
                    messageClassName = ClassName("com.example.protocol", "Message$index"),
                    handlerClassName = ClassName("com.example.handler", "Handler$index"),
                    dispatcher = "gate",
                )
            },
        )

        val fileNames = files.map { it.fileName }
        val catalog = files.first { it.fileName == "GeneratedGateMessageCatalog" }.file.toString()
        val chunk0 = files.first { it.fileName == "GeneratedGateMessageCatalogChunk0" }.file.toString()
        val chunk1 = files.first { it.fileName == "GeneratedGateMessageCatalogChunk1" }.file.toString()

        assertContains(fileNames, "GeneratedGateMessageCatalog")
        assertContains(fileNames, "GeneratedGateMessageCatalogChunk0")
        assertContains(fileNames, "GeneratedGateMessageCatalogChunk1")
        assertContains(catalog, "object GeneratedGateMessageCatalog : MessageCatalog")
        assertContains(catalog, "addAll(GeneratedGateMessageCatalogChunk0.bindings)")
        assertContains(catalog, "addAll(GeneratedGateMessageCatalogChunk1.bindings)")
        assertContains(chunk0, "internal object GeneratedGateMessageCatalogChunk0")
        assertContains(chunk0, "val bindings: List<MessageCatalogEntry> = listOf(")
        assertContains(chunk1, "internal object GeneratedGateMessageCatalogChunk1")
    }

    @Test
    fun `uses single message catalog file when binding count drops below chunk threshold`() {
        val files = AsteriaMessageCatalogCodeGenerator.buildFiles(
            generatedPackage = "com.example.generated",
            typeNamePart = "Gate",
            bindings = listOf(
                MessageCatalogBindingModel(
                    messageClassName = ClassName("com.example.protocol", "LoginRequest"),
                    handlerClassName = ClassName("com.example.handler", "LoginHandler"),
                    dispatcher = "gate",
                ),
            ),
        )

        val fileNames = files.map { it.fileName }
        val catalog = files.single { it.fileName == "GeneratedGateMessageCatalog" }.file.toString()

        assertFalse(fileNames.any { it.startsWith("GeneratedGateMessageCatalogChunk") })
        assertContains(catalog, "object GeneratedGateMessageCatalog : MessageCatalog")
        assertContains(catalog, "val bindings: List<MessageCatalogEntry> = listOf(")
        assertContains(catalog, "LoginRequest::class")
        assertContains(catalog, "LoginHandler::class")
    }
}
