package io.github.realmlabs.asteria.script.engine.groovy

import groovy.lang.Binding
import groovy.lang.GroovyClassLoader
import groovy.lang.Script
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.script.*
import org.codehaus.groovy.runtime.InvokerHelper
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Script engine that compiles artifact bodies as Groovy classes or top-level Groovy scripts.
 *
 * Classes must implement one of Asteria's script interfaces. Top-level scripts receive a Groovy binding with `context`,
 * `runtime`, `services`, `request`, `artifact`, `metadata`, `resources`, `tables`, and `cancellation`. Node scripts also
 * receive `target` and `nodeAddress`; actor scripts also receive `actor`, `target`, and `actorPath`.
 *
 * The compiled class is cached by checksum when the artifact provides one, otherwise by the artifact bytes. When the
 * cache reaches [classCacheSize], it is cleared as a whole; use stable checksums if scripts are reused frequently.
 */
class GroovyScriptEngine(
    override val name: String = "groovy",
    private val classCacheSize: Int = 64,
    private val metrics: Metrics = NoopMetrics,
) : ScriptEngine {
    private val logger = LoggerFactory.getLogger(GroovyScriptEngine::class.java)
    private val cache: MutableMap<String, KClass<*>> = ConcurrentHashMap()

    override fun compile(artifact: ScriptArtifact): CompiledScript {
        val tags = artifact.metricTags()
        val startedAt = System.nanoTime()
        metrics.counter("asteria.script.engine.compile.total", tags).increment()
        return try {
            loadClass(artifact).toGroovyCompiledScript()
        } catch (error: Throwable) {
            metrics.counter("asteria.script.engine.compile.failed.total", tags).increment()
            logger.error("Groovy script compile failed name={}", artifact.name, error)
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
        val loaded = GroovyClassLoader(javaClass.classLoader).parseClass(String(artifact.body, UTF_8)).kotlin
        if (cache.size >= classCacheSize) {
            cache.clear()
            metrics.counter("asteria.script.engine.cache.clear.total", artifact.metricTags()).increment()
        }
        cache[key] = loaded
        return loaded
    }

    private fun ScriptArtifact.cacheKey(): String {
        return checksum ?: "${name}_${engine}_${body.contentHashCode()}"
    }

    private fun ScriptArtifact.metricTags(): MetricTags {
        return MetricTags.of("engine" to engine)
    }
}

private fun KClass<*>.toGroovyCompiledScript(): CompiledScript {
    if (Script::class.java.isAssignableFrom(java)) {
        return CompiledScript { context ->
            val script = InvokerHelper.createScript(java, context.toGroovyBinding())
            script.run()
        }
    }
    return toCompiledScript()
}

private fun ScriptContext.toGroovyBinding(): Binding {
    return Binding(
        buildMap {
            put("context", this@toGroovyBinding)
            put("runtime", runtime)
            put("services", services)
            put("request", request)
            put("artifact", artifact)
            put("metadata", metadata)
            put("resources", resources)
            put("tables", tables)
            put("cancellation", cancellation)
            when (this@toGroovyBinding) {
                is NodeScriptContext<*> -> {
                    put("target", target)
                    put("nodeAddress", nodeAddress)
                }

                is ActorScriptContext<*> -> {
                    put("actor", actor)
                    put("target", target)
                    put("actorPath", actorPath)
                }
            }
        },
    )
}
