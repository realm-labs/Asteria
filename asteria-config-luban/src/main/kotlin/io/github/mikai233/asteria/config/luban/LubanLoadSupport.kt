package io.github.mikai233.asteria.config.luban

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name
import kotlin.reflect.KClass

interface LubanLoadReport {
    val dataDir: Path
    val files: List<Path>
    val checksum: String
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

internal fun checksum(files: Map<Path, ByteArray>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.toSortedMap(compareBy<Path> { it.name }.thenBy { it.toString() }).forEach { (path, bytes) ->
        digest.update(path.name.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(bytes)
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun Method.isLoadMethod(): Boolean {
    return name == "load" &&
        parameterCount == 1 &&
        parameterTypes.single() == String::class.java
}
