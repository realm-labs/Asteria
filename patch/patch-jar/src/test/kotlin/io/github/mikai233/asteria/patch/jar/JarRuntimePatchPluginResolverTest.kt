package io.github.mikai233.asteria.patch.jar

import io.github.mikai233.asteria.patch.InMemoryPatchArtifactStore
import io.github.mikai233.asteria.patch.PatchArtifact
import io.github.mikai233.asteria.patch.PatchCompatibility
import io.github.mikai233.asteria.patch.PatchId
import io.github.mikai233.asteria.patch.PatchInstallContext
import io.github.mikai233.asteria.patch.RuntimePatch
import io.github.mikai233.asteria.patch.RuntimePatchPlugin
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertIs

class JarRuntimePatchPluginResolverTest {
    @Test
    fun resolverLoadsPluginFromManifestClass() = runBlocking {
        val jar = jarWithPluginClass(TestPatchPlugin::class.java)
        val artifact = PatchArtifact(
            name = "test-patch.jar",
            checksum = "sha256:${jar.sha256Hex()}",
        )
        val store = InMemoryPatchArtifactStore().also { it.put(artifact, jar) }
        val resolver = JarRuntimePatchPluginResolver(store)

        val plugin = resolver.resolve(
            RuntimePatch(
                id = PatchId("test"),
                name = "test",
                artifact = artifact,
                compatibility = PatchCompatibility("game", setOf("1.0.0")),
                sequence = 1,
            ),
        )

        assertIs<TestPatchPlugin>(plugin)
        Unit
    }

    class TestPatchPlugin : RuntimePatchPlugin {
        override suspend fun install(context: PatchInstallContext) {
        }
    }

    private fun jarWithPluginClass(type: Class<*>): ByteArray {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(JarRuntimePatchPluginResolver.PATCH_CLASS_NAME, type.name)
        }
        val classEntry = type.name.replace('.', '/') + ".class"
        val classBytes = requireNotNull(type.classLoader.getResourceAsStream(classEntry)) {
            "class bytes for ${type.name} not found"
        }.use { it.readBytes() }
        val output = ByteArrayOutputStream()
        JarOutputStream(output, manifest).use { jar ->
            jar.putNextEntry(JarEntry(classEntry))
            jar.write(classBytes)
            jar.closeEntry()
        }
        return output.toByteArray()
    }

    private fun ByteArray.sha256Hex(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
    }
}
