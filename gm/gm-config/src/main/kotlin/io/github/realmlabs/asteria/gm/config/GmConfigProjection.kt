package io.github.realmlabs.asteria.gm.config

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass

/**
 * Projects typed config rows into generic values suitable for GM HTTP responses.
 */
interface ConfigRowProjector {
    fun supports(rowType: KClass<*>): Boolean

    fun describe(rowType: KClass<*>): List<GmConfigFieldDescriptor>

    fun project(row: Any): Map<String, Any?>
}

/**
 * Reflection-based row projector for ordinary Kotlin/Java data objects.
 *
 * It reads public bean getters and public fields. Nested objects are converted recursively up to [maxDepth], while
 * collections and maps are kept as JSON-friendly structures.
 */
class ReflectionConfigRowProjector(
    private val maxDepth: Int = 4,
) : ConfigRowProjector {
    init {
        require(maxDepth >= 0) { "GM config projection max depth must not be negative" }
    }

    override fun supports(rowType: KClass<*>): Boolean = true

    override fun describe(rowType: KClass<*>): List<GmConfigFieldDescriptor> {
        val javaType = rowType.java
        val getters = javaType.projectableGetters()
        val getterFields = getters.map { method ->
            GmConfigFieldDescriptor(
                name = method.propertyName(),
                type = method.returnType.typeName,
                nullable = !method.returnType.isPrimitive,
            )
        }
        val methodNames = getterFields.mapTo(mutableSetOf()) { it.name }
        val fieldFields = javaType.fields
            .asSequence()
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filterNot { it.name in methodNames }
            .map {
                GmConfigFieldDescriptor(
                    name = it.name,
                    type = it.type.typeName,
                    nullable = !it.type.isPrimitive,
                )
            }
            .toList()
        return (getterFields + fieldFields).sortedBy { it.name }
    }

    override fun project(row: Any): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return projectObject(row, 0) as? Map<String, Any?>
            ?: error("GM config row projection did not produce an object")
    }

    private fun projectObject(
        value: Any?,
        depth: Int,
    ): Any? {
        if (value == null || value.isScalar()) {
            return value
        }
        if (depth >= maxDepth) {
            return value.toString()
        }
        return when (value) {
            is Enum<*> -> value.name
            is Iterable<*> -> value.map { projectObject(it, depth + 1) }
            is Array<*> -> value.map { projectObject(it, depth + 1) }
            is Map<*, *> -> value.entries.associate { (key, entryValue) ->
                key.toString() to projectObject(entryValue, depth + 1)
            }

            else -> value.projectBean(depth)
        }
    }

    private fun Any.projectBean(depth: Int): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val getters = javaClass.projectableGetters()
        getters.forEach { method ->
            result[method.propertyName()] = projectObject(method.invoke(this), depth + 1)
        }
        javaClass.fields
            .asSequence()
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filterNot { it.name in result }
            .forEach { field ->
                result[field.name] = projectObject(field.get(this), depth + 1)
            }
        return result
    }

    private fun Any.isScalar(): Boolean {
        return this is String ||
                this is Number ||
                this is Boolean ||
                this is Char ||
                this is BigInteger ||
                this is BigDecimal
    }
}

/**
 * Chooses a row projector by row type.
 */
class CompositeConfigRowProjector(
    private val projectors: List<ConfigRowProjector>,
) : ConfigRowProjector {
    init {
        require(projectors.isNotEmpty()) { "GM config row projectors must not be empty" }
    }

    override fun supports(rowType: KClass<*>): Boolean {
        return projectors.any { it.supports(rowType) }
    }

    override fun describe(rowType: KClass<*>): List<GmConfigFieldDescriptor> {
        return projector(rowType).describe(rowType)
    }

    override fun project(row: Any): Map<String, Any?> {
        return projector(row::class).project(row)
    }

    private fun projector(rowType: KClass<*>): ConfigRowProjector {
        return projectors.firstOrNull { it.supports(rowType) }
            ?: error("GM config row projector for ${rowType.qualifiedName} not found")
    }
}

private fun Class<*>.projectableGetters(): List<Method> {
    return methods
        .asSequence()
        .filter { Modifier.isPublic(it.modifiers) }
        .filterNot { Modifier.isStatic(it.modifiers) }
        .filter { it.parameterCount == 0 }
        .filter { it.returnType != Void.TYPE }
        .filterNot { it.name == "getClass" }
        .filter { it.name.startsWith("get") && it.name.length > 3 || it.name.startsWith("is") && it.name.length > 2 }
        .sortedBy { it.propertyName() }
        .toList()
}

private fun Method.propertyName(): String {
    val raw = when {
        name.startsWith("get") -> name.removePrefix("get")
        name.startsWith("is") -> name.removePrefix("is")
        else -> name
    }
    return raw.replaceFirstChar { it.lowercase() }
}
