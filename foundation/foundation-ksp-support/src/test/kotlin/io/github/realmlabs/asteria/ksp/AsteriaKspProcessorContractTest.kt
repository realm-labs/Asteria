package io.github.realmlabs.asteria.ksp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsteriaKspProcessorContractTest {
    @Test
    fun annotatedSymbolsAreNotDroppedByGlobalValidateOrTypeFiltering() {
        val processorFiles = findAsteriaRoot()
            .let { root -> Files.walk(root).use { paths -> paths.filter { it.name.endsWith("SymbolProcessorProvider.kt") }.toList() } }
            .filter { "src${Path.of("").fileSystem.separator}main" in it.toString() }

        assertTrue(processorFiles.isNotEmpty(), "expected to find Asteria KSP processor sources")

        for (file in processorFiles) {
            val source = file.readText()
            assertFalse(
                "com.google.devtools.ksp.validate" in source || ".validate()" in source,
                "$file must not use whole-symbol validate() to filter annotated symbols",
            )
            assertFalse(
                GET_SYMBOLS_THEN_FILTER_CLASS.containsMatchIn(source),
                "$file must not filter annotated symbols with filterIsInstance<KSClassDeclaration>()",
            )
        }
    }

    private fun findAsteriaRoot(): Path {
        return generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { candidate ->
                val settings = candidate.resolve("settings.gradle.kts")
                Files.exists(settings) && settings.readText().contains("rootProject.name = \"Asteria\"")
            }
    }

    private companion object {
        val GET_SYMBOLS_THEN_FILTER_CLASS = Regex(
            "getSymbolsWithAnnotation\\([\\s\\S]{0,240}?\\.filterIsInstance<KSClassDeclaration>\\(",
        )
    }
}
