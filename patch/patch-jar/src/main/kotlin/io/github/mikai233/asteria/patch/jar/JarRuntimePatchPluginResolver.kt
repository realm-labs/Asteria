package io.github.mikai233.asteria.patch.jar

import io.github.mikai233.asteria.patch.PatchArtifactStore
import io.github.mikai233.asteria.patch.PatchId
import io.github.mikai233.asteria.patch.RuntimePatch
import io.github.mikai233.asteria.patch.RuntimePatchPlugin
import io.github.mikai233.asteria.patch.RuntimePatchPluginResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import kotlin.io.path.outputStream

class JarRuntimePatchPluginResolver(
    private val artifacts: PatchArtifactStore,
    private val parentClassLoader: ClassLoader = JarRuntimePatchPluginResolver::class.java.classLoader,
    private val cacheDirectory: Path? = null,
    private val loadPolicy: RuntimePatchPluginLoadPolicy = RuntimePatchPluginLoadPolicy.AllowAll,
) : RuntimePatchPluginResolver {
    private val cache: MutableMap<PatchId, LoadedPatchPlugin> = ConcurrentHashMap()

    override suspend fun resolve(patch: RuntimePatch): RuntimePatchPlugin {
        val key = patch.id
        return cache[key]?.newInstance() ?: load(patch).also { cache[key] = it }.newInstance()
    }

    override suspend fun evict(patch: RuntimePatch) {
        cache.remove(patch.id)?.close()
    }

    private suspend fun load(patch: RuntimePatch): LoadedPatchPlugin {
        val bytes = artifacts.load(patch.artifact)
        val jar = withContext(Dispatchers.IO) {
            val file = cacheDirectory
                ?.also { Files.createDirectories(it) }
                ?.resolve("${patch.id.value}-${patch.artifact.checksum.hashCode()}.jar")
                ?: Files.createTempFile("asteria-patch-${patch.id.value}", ".jar")
            file.outputStream().use { it.write(bytes) }
            file.toFile().deleteOnExit()
            file
        }
        val className = withContext(Dispatchers.IO) {
            JarFile(jar.toFile()).use { jarFile ->
                jarFile.manifest?.mainAttributes?.getValue(PATCH_CLASS_NAME)
            }
        }
        val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), parentClassLoader)
        return if (className != null) {
            LoadedPatchPlugin(patch.allowed(loader.loadClass(className)), loader)
        } else {
            val pluginClass = ServiceLoader.load(RuntimePatchPlugin::class.java, loader)
                .firstOrNull()
                ?.javaClass
                ?: error("patch jar ${patch.artifact.name} must declare $PATCH_CLASS_NAME or service provider")
            LoadedPatchPlugin(patch.allowed(pluginClass), loader)
        }
    }

    private fun RuntimePatch.allowed(type: Class<*>): Class<*> {
        require(loadPolicy.allow(this, type)) {
            "patch ${id.value} plugin class ${type.name} is rejected by load policy"
        }
        return type
    }

    companion object {
        const val PATCH_CLASS_NAME: String = "Patch-Class"
    }
}

fun interface RuntimePatchPluginLoadPolicy {
    fun allow(
        patch: RuntimePatch,
        pluginClass: Class<*>,
    ): Boolean

    companion object {
        val AllowAll: RuntimePatchPluginLoadPolicy = RuntimePatchPluginLoadPolicy { _, _ -> true }

        fun packagePrefixes(vararg prefixes: String): RuntimePatchPluginLoadPolicy {
            require(prefixes.isNotEmpty()) { "patch plugin package prefixes must not be empty" }
            prefixes.forEach { require(it.isNotBlank()) { "patch plugin package prefix must not be blank" } }
            return RuntimePatchPluginLoadPolicy { _, pluginClass ->
                prefixes.any { prefix -> pluginClass.name.startsWith(prefix) }
            }
        }
    }
}

private class LoadedPatchPlugin(
    private val type: Class<*>,
    private val loader: URLClassLoader?,
) {
    fun newInstance(): RuntimePatchPlugin {
        return type.getDeclaredConstructor().newInstance() as RuntimePatchPlugin
    }

    fun close() {
        loader?.close()
    }
}
