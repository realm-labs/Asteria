package io.github.mikai233.asteria.script.engine.jar

import io.github.mikai233.asteria.script.CompiledScript
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptEngine
import io.github.mikai233.asteria.script.toCompiledScript
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import kotlin.reflect.KClass

class JarScriptEngine(
    override val name: String = "jar",
    private val classCacheSize: Int = 64,
) : ScriptEngine {
    private val cache: MutableMap<String, KClass<*>> = ConcurrentHashMap()

    override fun compile(artifact: ScriptArtifact): CompiledScript {
        return loadClass(artifact).toCompiledScript()
    }

    private fun loadClass(artifact: ScriptArtifact): KClass<*> {
        val key = artifact.cacheKey()
        cache[key]?.let { return it }
        val loaded = loadClassFromJar(artifact)
        if (cache.size >= classCacheSize) {
            cache.clear()
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

    companion object {
        const val SCRIPT_CLASS_NAME = "Script-Class"
    }
}
