package io.github.mikai233.asteria.config.luban

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.io.path.name
import kotlin.reflect.KClass

data class LubanPreloadOptions(
    val enabled: Boolean = true,
    val maxConcurrency: Int = 4,
) {
    init {
        require(maxConcurrency in 1..64) { "Luban preload max concurrency must be in 1..64" }
    }
}

interface LubanLoadReport {
    val files: List<String>
    val checksum: String
}

/**
 * Source of raw Luban table files.
 *
 * Runtime reloads can use [MemoryLubanDataSource] directly from config-center artifacts. Local development and
 * publication jobs can use [DirectoryLubanDataSource].
 */
interface LubanDataSource {
    suspend fun list(extension: String): Map<String, ByteArray>
}

class MemoryLubanDataSource(
    files: Map<String, ByteArray>,
) : LubanDataSource {
    private val files: Map<String, ByteArray> = files.mapKeys { (name, _) ->
        normalizeLubanFileName(name)
    }.mapValues { (_, bytes) ->
        bytes.copyOf()
    }

    override suspend fun list(extension: String): Map<String, ByteArray> {
        return files
            .filterKeys { it.endsWith(extension) }
            .toSortedMap()
            .mapValues { it.value.copyOf() }
    }
}

class DirectoryLubanDataSource(
    private val dataDir: Path,
    private val preloadOptions: LubanPreloadOptions = LubanPreloadOptions(),
    private val fileResolver: (String) -> Path = { file -> dataDir.resolve(file) },
) : LubanDataSource {
    override suspend fun list(extension: String): Map<String, ByteArray> {
        val files = Files.list(dataDir).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path) && path.name.endsWith(extension) }
                .sorted()
                .toList()
        }
        if (!preloadOptions.enabled) {
            return files.associate { path ->
                path.name to Files.readAllBytes(fileResolver(path.name))
            }
        }
        val semaphore = Semaphore(preloadOptions.maxConcurrency)

        return coroutineScope {
            files.map { path ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        path.name to Files.readAllBytes(path)
                    }
                }
            }.awaitAll().toMap(linkedMapOf())
        }
    }
}

internal fun instantiateLubanTables(
    tablesType: KClass<out Any>,
    loaderName: String,
    load: (file: String, returnType: Class<*>) -> Any,
): Any {
    val constructor = tablesType.java.constructors.firstOrNull { it.parameterCount == 1 }
        ?: error("Luban tables type ${tablesType.qualifiedName} must have a single-argument loader constructor")
    val loaderType = constructor.parameterTypes.single()
    require(loaderType.isInterface) {
        "Luban tables loader type ${loaderType.name} must be an interface"
    }

    val loader = Proxy.newProxyInstance(loaderType.classLoader, arrayOf(loaderType)) { proxy, method, args ->
        when {
            method.isLoadMethod() -> load(args?.single() as String, method.returnType)
            method.name == "toString" && method.parameterCount == 0 -> "$loaderName(${loaderType.name})"
            method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
            method.name == "equals" && method.parameterCount == 1 -> proxy === args?.single()
            else -> error("unsupported Luban loader method ${method.name}")
        }
    }

    return try {
        constructor.trySetAccessible()
        constructor.newInstance(loader)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}

internal fun extractTableComponents(tables: Any): List<Any> {
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

internal fun checksumByName(files: Map<String, ByteArray>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.toSortedMap().forEach { (name, bytes) ->
        digest.update(name.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(bytes)
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun fileNameWithExtension(
    file: String,
    extension: String,
): String {
    val normalized = normalizeLubanFileName(file)
    return if (normalized.endsWith(extension)) normalized else "$normalized$extension"
}

internal fun normalizeLubanFileName(file: String): String {
    val normalized = file.trim()
        .replace('\\', '/')
        .removePrefix("./")
    require(normalized.isNotBlank()) { "Luban data file name must not be blank" }
    require(!normalized.startsWith("/")) { "Luban data file name must be relative: $file" }
    require(!normalized.contains("//")) { "Luban data file name must not contain empty segments: $file" }
    require(normalized.split('/').none { it == "." || it == ".." }) {
        "Luban data file name must not contain dot segments: $file"
    }
    return normalized
}

private fun Method.isLoadMethod(): Boolean {
    return name == "load" &&
        parameterCount == 1 &&
        parameterTypes.single() == String::class.java
}
