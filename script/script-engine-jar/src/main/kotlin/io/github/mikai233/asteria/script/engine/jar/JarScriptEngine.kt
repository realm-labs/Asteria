package io.github.mikai233.asteria.script.engine.jar

import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.github.mikai233.asteria.script.CompiledScript
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptEngine
import io.github.mikai233.asteria.script.toCompiledScript
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import kotlin.reflect.KClass

class JarScriptEngine(
    override val name: String = "jar",
    private val classCacheSize: Int = 64,
    private val metrics: Metrics = NoopMetrics,
) : ScriptEngine {
    private val logger = LoggerFactory.getLogger(JarScriptEngine::class.java)
    private val cache: MutableMap<String, KClass<*>> = ConcurrentHashMap()

    override fun compile(artifact: ScriptArtifact): CompiledScript {
        val tags = artifact.metricTags()
        val startedAt = System.nanoTime()
        metrics.counter("asteria.script.engine.compile.total", tags).increment()
        return try {
            loadClass(artifact).toCompiledScript()
        } catch (error: Throwable) {
            metrics.counter("asteria.script.engine.compile.failed.total", tags).increment()
            logger.error("Jar script compile failed name={}", artifact.name, error)
            throw error
        } finally {
            metrics.timer("asteria.script.engine.compile.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun loadClass(artifact: ScriptArtifact): KClass<*> {
        val key = artifact.cacheKey()
        cache[key]?.let {
            metrics.counter("asteria.script.engine.cache.hit.total", artifact.metricTags()).increment()
            return it
        }
        metrics.counter("asteria.script.engine.cache.miss.total", artifact.metricTags()).increment()
        val loaded = loadClassFromJar(artifact)
        if (cache.size >= classCacheSize) {
            cache.clear()
            metrics.counter("asteria.script.engine.cache.clear.total", artifact.metricTags()).increment()
        }
        cache[key] = loaded
        return loaded
    }

    private fun loadClassFromJar(artifact: ScriptArtifact): KClass<*> {
        val file = File.createTempFile(artifact.name, ".jar")
        file.deleteOnExit()
        file.writeBytes(artifact.body)
        val scriptClassName = JarFile(file).use { jar ->
            requireNotNull(jar.manifest?.mainAttributes?.getValue(SCRIPT_CLASS_NAME)) {
                "$SCRIPT_CLASS_NAME not found in script jar manifest"
            }
        }
        val loader = URLClassLoader(arrayOf(file.toURI().toURL()), javaClass.classLoader)
        return loader.loadClass(scriptClassName).kotlin
    }

    private fun ScriptArtifact.cacheKey(): String {
        return checksum ?: "${name}_${engine}_${body.contentHashCode()}"
    }

    private fun ScriptArtifact.metricTags(): MetricTags {
        return MetricTags.of("engine" to engine)
    }

    companion object {
        const val SCRIPT_CLASS_NAME = "Script-Class"
    }
}
