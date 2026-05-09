package io.github.realmlabs.asteria.patch.jar

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import kotlin.io.path.outputStream

/**
 * Loads runtime patch plugins from patch artifact JARs.
 *
 * The resolver first looks for a `Patch-Class` manifest attribute and falls back to a `ServiceLoader` provider for
 * [RuntimePatchPlugin]. The loaded class and its classloader are cached by patch id, but each [resolve] call returns a
 * fresh plugin instance. Call [evict] when a patch version is no longer active so its cached classloader can be closed.
 */
class JarRuntimePatchPluginResolver(
    private val artifacts: PatchArtifactStore,
    private val parentClassLoader: ClassLoader = JarRuntimePatchPluginResolver::class.java.classLoader,
    private val cacheDirectory: Path? = null,
    private val loadPolicy: RuntimePatchPluginLoadPolicy = RuntimePatchPluginLoadPolicy.AllowAll,
    private val metrics: Metrics = NoopMetrics,
) : RuntimePatchPluginResolver {
    private val logger = LoggerFactory.getLogger(JarRuntimePatchPluginResolver::class.java)
    private val cache: MutableMap<PatchId, LoadedPatchPlugin> = ConcurrentHashMap()

    override suspend fun resolve(patch: RuntimePatchDescriptor): RuntimePatchPlugin {
        val key = patch.id
        val tags = patch.metricTags()
        metrics.counter("asteria.patch.jar.resolve.total", tags).increment()
        val cached = cache[key]
        if (cached != null) {
            metrics.counter("asteria.patch.jar.cache.hit.total", tags).increment()
            return cached.newInstance()
        }
        metrics.counter("asteria.patch.jar.cache.miss.total", tags).increment()
        return load(patch).also { cache[key] = it }.newInstance()
    }

    override suspend fun evict(patch: RuntimePatchDescriptor) {
        cache.remove(patch.id)?.close()
        metrics.counter("asteria.patch.jar.cache.evict.total", patch.metricTags()).increment()
    }

    private suspend fun load(patch: RuntimePatchDescriptor): LoadedPatchPlugin {
        val tags = patch.metricTags()
        val startedAt = System.nanoTime()
        metrics.counter("asteria.patch.jar.load.total", tags).increment()
        try {
            val bytes = artifacts.load(patch.artifact)
            metrics.counter("asteria.patch.jar.load.bytes.total", tags).increment(bytes.size.toLong())
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
        } catch (error: Throwable) {
            metrics.counter("asteria.patch.jar.load.failed.total", tags).increment()
            logger.error("patch jar load failed id={} artifact={}", patch.id.value, patch.artifact.name, error)
            throw error
        } finally {
            metrics.timer("asteria.patch.jar.load.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun RuntimePatchDescriptor.allowed(type: Class<*>): Class<*> {
        require(loadPolicy.allow(this, type)) {
            "patch ${id.value} plugin class ${type.name} is rejected by load policy"
        }
        return type
    }

    companion object {
        const val PATCH_CLASS_NAME: String = "Patch-Class"
    }
}

private fun RuntimePatchDescriptor.metricTags(): MetricTags {
    return MetricTags.of("app" to compatibility.appName)
}

fun interface RuntimePatchPluginLoadPolicy {
    /**
     * Checks the selected plugin class before it is instantiated.
     *
     * This is a class-selection guard, not a sandbox. Patch code still runs with the permissions of this process after
     * the plugin is loaded.
     */
    fun allow(
        patch: RuntimePatchDescriptor,
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
