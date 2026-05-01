package io.github.mikai233.asteria.script.engine.groovy

import groovy.lang.GroovyClassLoader
import io.github.mikai233.asteria.script.CompiledScript
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptEngine
import io.github.mikai233.asteria.script.toCompiledScript
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class GroovyScriptEngine(
    override val name: String = "groovy",
    private val classCacheSize: Int = 64,
) : ScriptEngine {
    private val cache: MutableMap<String, KClass<*>> = ConcurrentHashMap()

    override fun compile(artifact: ScriptArtifact): CompiledScript {
        return loadClass(artifact).toCompiledScript()
    }

    private fun loadClass(artifact: ScriptArtifact): KClass<*> {
        val key = artifact.cacheKey()
        cache[key]?.let { return it }
        val loaded = GroovyClassLoader(javaClass.classLoader).parseClass(String(artifact.body)).kotlin
        if (cache.size >= classCacheSize) {
            cache.clear()
        }
        cache[key] = loaded
        return loaded
    }

    private fun ScriptArtifact.cacheKey(): String {
        return checksum ?: "${name}_${engine}_${body.contentHashCode()}"
    }
}
