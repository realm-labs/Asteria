package io.github.mikai233.asteria.config.luban

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mikai233.asteria.config.ConfigLoader
import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.ConfigSnapshot
import io.github.mikai233.asteria.config.DefaultConfigSnapshot
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name
import kotlin.reflect.KClass

class LubanJsonConfigLoader(
    private val tablesType: KClass<out Any>,
    private val dataDir: Path,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val charset: Charset = StandardCharsets.UTF_8,
    private val fileResolver: (String) -> Path = { dataDir.resolve("$it.json") },
    private val includeTableComponents: Boolean = true,
    private val revisionFactory: (LubanJsonLoadReport) -> ConfigRevision = { report ->
        ConfigRevision(version = report.checksum, checksum = report.checksum)
    },
) : ConfigLoader {
    override suspend fun load(): ConfigSnapshot {
        val loadedFiles = linkedMapOf<Path, ByteArray>()
        val constructor = tablesType.java.constructors.firstOrNull { it.parameterCount == 1 }
            ?: error("Luban tables type ${tablesType.qualifiedName} must have a single-argument loader constructor")
        val loaderType = constructor.parameterTypes.single()
        require(loaderType.isInterface) {
            "Luban tables loader type ${loaderType.name} must be an interface"
        }

        val loader = createJsonLoaderProxy(loaderType) { file ->
            val path = fileResolver(file)
            val bytes = Files.readAllBytes(path)
            loadedFiles[path.normalize()] = bytes
            objectMapper.readTree(bytes.toString(charset))
        }

        val tables = try {
            constructor.trySetAccessible()
            constructor.newInstance(loader)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        val report = LubanJsonLoadReport(
            dataDir = dataDir,
            files = loadedFiles.keys.toList(),
            checksum = checksum(loadedFiles),
        )
        val components = buildList {
            add(tables)
            if (includeTableComponents) {
                addAll(extractTableComponents(tables))
            }
        }

        return DefaultConfigSnapshot(
            revision = revisionFactory(report),
            components = components,
            componentsByType = mapOf(tablesType to tables),
        )
    }
}

data class LubanJsonLoadReport(
    val dataDir: Path,
    val files: List<Path>,
    val checksum: String,
)

private fun createJsonLoaderProxy(
    loaderType: Class<*>,
    loadJson: (String) -> JsonNode,
): Any {
    return Proxy.newProxyInstance(loaderType.classLoader, arrayOf(loaderType)) { proxy, method, args ->
        when {
            isLoadMethod(method) -> loadJson(args?.single() as String)
            method.name == "toString" && method.parameterCount == 0 -> "AsteriaLubanJsonLoader(${loaderType.name})"
            method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
            method.name == "equals" && method.parameterCount == 1 -> proxy === args?.single()
            else -> error("unsupported Luban loader method ${method.name}")
        }
    }
}

private fun isLoadMethod(method: Method): Boolean {
    return method.name == "load" &&
        method.parameterCount == 1 &&
        method.parameterTypes.single() == String::class.java
}

private fun extractTableComponents(tables: Any): List<Any> {
    return tables.javaClass.methods
        .asSequence()
        .filter { method ->
            Modifier.isPublic(method.modifiers) &&
                method.parameterCount == 0 &&
                method.name.startsWith("getTb") &&
                method.returnType != Void.TYPE &&
                method.declaringClass != Any::class.java
        }
        .mapNotNull { method -> method.invoke(tables) }
        .toList()
}

private fun checksum(files: Map<Path, ByteArray>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.toSortedMap(compareBy<Path> { it.name }.thenBy { it.toString() }).forEach { (path, bytes) ->
        digest.update(path.name.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(bytes)
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
