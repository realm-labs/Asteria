package io.github.realmlabs.asteria.patch.jar

import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JarRuntimePatchPluginResolverTest {
    @Test
    fun resolverLoadsPluginFromManifestClass(): Unit = runBlocking {
        val jar = jarWithPluginClass(TestPatchPlugin::class.java)
        val artifact = PatchArtifact(
            name = "test-patch.jar",
            checksum = "sha256:${jar.sha256Hex()}",
        )
        val store = InMemoryPatchArtifactStore().also { it.put(artifact, jar) }
        val resolver = JarRuntimePatchPluginResolver(store)

        val plugin = resolver.resolve(
            RuntimePatchDescriptor(
                id = PatchId("test"),
                artifact = artifact,
                compatibility = PatchCompatibility("game", setOf("1.0.0")),
                name = "test",
                revision = 1,
            ),
        )

        assertIs<TestPatchPlugin>(plugin)
    }

    @Test
    fun evictDropsLoadedPluginCache(): Unit = runBlocking {
        val jar = jarWithPluginClass(TestPatchPlugin::class.java)
        val artifact = PatchArtifact(
            name = "test-patch.jar",
            checksum = "sha256:${jar.sha256Hex()}",
        )
        val store = CountingPatchArtifactStore(artifact, jar)
        val resolver = JarRuntimePatchPluginResolver(store)
        val patch = RuntimePatchDescriptor(
            id = PatchId("test"),
            artifact = artifact,
            compatibility = PatchCompatibility("game", setOf("1.0.0")),
            name = "test",
            revision = 1,
        )

        resolver.resolve(patch)
        resolver.resolve(patch)
        resolver.evict(patch)
        resolver.resolve(patch)

        assertEquals(2, store.loadCount)
    }

    @Test
    fun resolverReloadsWhenSamePatchIdUsesDifferentArtifact(): Unit = runBlocking {
        val firstJar = jarWithPluginClass(TestPatchPlugin::class.java)
        val secondJar = jarWithPluginClass(OtherPatchPlugin::class.java)
        val firstArtifact = PatchArtifact(
            name = "test-patch.jar",
            checksum = "sha256:${firstJar.sha256Hex()}",
        )
        val secondArtifact = PatchArtifact(
            name = "test-patch.jar",
            checksum = "sha256:${secondJar.sha256Hex()}",
        )
        val store = CountingPatchArtifactStore(
            mapOf(
                firstArtifact to firstJar,
                secondArtifact to secondJar,
            ),
        )
        val resolver = JarRuntimePatchPluginResolver(store)

        assertIs<TestPatchPlugin>(resolver.resolve(patch(firstArtifact)))
        assertIs<OtherPatchPlugin>(resolver.resolve(patch(secondArtifact)))

        assertEquals(2, store.loadCount)
    }

    @Test
    fun loadPolicyCanRejectPluginClass(): Unit = runBlocking {
        val jar = jarWithPluginClass(TestPatchPlugin::class.java)
        val artifact = PatchArtifact(
            name = "test-patch.jar",
            checksum = "sha256:${jar.sha256Hex()}",
        )
        val store = InMemoryPatchArtifactStore().also { it.put(artifact, jar) }
        val resolver = JarRuntimePatchPluginResolver(
            artifacts = store,
            loadPolicy = RuntimePatchPluginLoadPolicy.packagePrefixes("not.allowed."),
        )

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(patch(artifact))
        }
    }

    class TestPatchPlugin : RuntimePatchPlugin {
        override suspend fun install(context: RuntimePatchInstallContext) {
        }
    }

    class OtherPatchPlugin : RuntimePatchPlugin {
        override suspend fun install(context: RuntimePatchInstallContext) {
        }
    }

    private fun jarWithPluginClass(type: Class<*>): ByteArray {
        return jarWithManifestClass(type, JarRuntimePatchPluginResolver.PATCH_CLASS_NAME)
    }

    private fun jarWithManifestClass(
        type: Class<*>,
        attributeName: String,
    ): ByteArray {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(attributeName, type.name)
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

    private fun patch(artifact: PatchArtifact): RuntimePatchDescriptor {
        return RuntimePatchDescriptor(
            id = PatchId("test"),
            artifact = artifact,
            compatibility = PatchCompatibility("game", setOf("1.0.0")),
            name = "test",
            revision = 1,
        )
    }

    private fun ByteArray.sha256Hex(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
    }

    private class CountingPatchArtifactStore(
        private val artifacts: Map<PatchArtifact, ByteArray>,
    ) : io.github.realmlabs.asteria.patch.PatchArtifactStore {
        constructor(
            artifact: PatchArtifact,
            bytes: ByteArray,
        ) : this(mapOf(artifact to bytes))

        var loadCount: Int = 0
            private set

        override suspend fun load(artifact: PatchArtifact): ByteArray {
            loadCount += 1
            return requireNotNull(artifacts[artifact]) { "test artifact $artifact not found" }
        }
    }
}
