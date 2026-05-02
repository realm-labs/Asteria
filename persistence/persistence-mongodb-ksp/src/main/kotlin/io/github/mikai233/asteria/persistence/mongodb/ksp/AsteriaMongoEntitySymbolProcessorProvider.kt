package io.github.mikai233.asteria.persistence.mongodb.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.mikai233.asteria.persistence.AsteriaMongoEntity
import io.github.mikai233.asteria.persistence.AsteriaMongoField
import io.github.mikai233.asteria.persistence.AsteriaMongoId
import io.github.mikai233.asteria.persistence.AsteriaMongoIgnore
import io.github.mikai233.asteria.persistence.AsteriaMongoValue

class AsteriaMongoEntitySymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AsteriaMongoEntitySymbolProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}

private class AsteriaMongoEntitySymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    options: Map<String, String>,
) : SymbolProcessor {
    private var generated = false
    private val allowedTypes: Set<String> = options["asteria.mongodb.allowedTypes"]
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        val symbols = resolver.getSymbolsWithAnnotation(AsteriaMongoEntity::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        val invalid = symbols.filterNot { it.validate() }
        if (invalid.isNotEmpty()) {
            return invalid
        }
        symbols.forEach { symbol ->
            val model = readModel(symbol) ?: return@forEach
            val file = AsteriaMongoEntityCodeGenerator.buildFile(model)
            val sourceFile = symbol.containingFile
            file.writeTo(codeGenerator, Dependencies(aggregating = false, *listOfNotNull(sourceFile).toTypedArray()))
        }
        generated = true
        return emptyList()
    }

    private fun readModel(symbol: KSClassDeclaration): MongoEntityCodegenModel? {
        val annotation = symbol.findAnnotation(AsteriaMongoEntity::class.qualifiedName!!) ?: return null
        val collectionName = annotation.stringArg("collection")
        if (collectionName.isBlank()) {
            logger.error("@AsteriaMongoEntity collection must not be blank", symbol)
            return null
        }
        val packageName = symbol.packageName.asString()
        val entityName = symbol.simpleName.asString()
        val wrapperName = annotation.stringArg("wrapperName").takeIf { it.isNotBlank() } ?: "Tracked$entityName"
        val nestedObjects = linkedMapOf<String, MongoNestedObjectModel>()
        val properties = symbol.getAllProperties()
            .filterNot { it.hasAnnotation(AsteriaMongoIgnore::class.qualifiedName!!) }
            .map { property ->
                readProperty(
                    property = property,
                    generatedPackage = packageName,
                    wrapperPrefix = wrapperName,
                    nestedObjects = nestedObjects,
                    visiting = mutableSetOf(),
                )
            }
            .toList()
        val annotatedIds = properties.filter { property ->
            symbol.property(property.name)?.hasAnnotation(AsteriaMongoId::class.qualifiedName!!) == true
        }
        val id = when {
            annotatedIds.size == 1 -> annotatedIds.single()
            annotatedIds.size > 1 -> {
                logger.error("Only one @AsteriaMongoId property is allowed", symbol)
                return null
            }

            else -> properties.singleOrNull { it.name == "id" } ?: run {
                logger.error("Mongo entity ${symbol.qualifiedName?.asString()} requires an id property or @AsteriaMongoId", symbol)
                return null
            }
        }
        if (id.type.isNullable) {
            logger.error("Mongo entity id must not be nullable", symbol)
            return null
        }
        val duplicateField = properties
            .groupBy { if (it.name == id.name) "_id" else it.fieldName }
            .filterValues { it.size > 1 }
            .keys
            .firstOrNull()
        if (duplicateField != null) {
            logger.error("duplicate generated Mongo field name $duplicateField", symbol)
            return null
        }
        if (properties.any { it.name != id.name && it.fieldName == "_id" }) {
            logger.error("Mongo field _id is reserved for the entity id", symbol)
            return null
        }
        if (!validateProperties(symbol, properties)) {
            return null
        }
        return MongoEntityCodegenModel(
            packageName = packageName,
            entityType = symbol.toClassName(),
            wrapperName = wrapperName,
            helperName = annotation.stringArg("helperName").takeIf { it.isNotBlank() } ?: "${entityName}Mongo",
            collectionName = collectionName,
            id = id,
            properties = properties,
            nestedObjects = nestedObjects.values.toList(),
        )
    }

    private fun readProperty(
        property: KSPropertyDeclaration,
        generatedPackage: String,
        wrapperPrefix: String,
        nestedObjects: MutableMap<String, MongoNestedObjectModel>,
        visiting: MutableSet<String>,
    ): MongoEntityPropertyModel {
        val type = property.type.resolve()
        val nestedType = nestedWrapperType(type, generatedPackage, wrapperPrefix, nestedObjects, visiting)
        return MongoEntityPropertyModel(
            name = property.simpleName.asString(),
            fieldName = property.findAnnotation(AsteriaMongoField::class.qualifiedName!!)
                ?.stringArg("name")
                ?.takeIf { it.isNotBlank() }
                ?: property.simpleName.asString(),
            type = type.toTypeName(),
            kind = if (nestedType == null) propertyKind(type) else MongoEntityPropertyKind.Object,
            trackedType = nestedType ?: type.toTypeName(),
        )
    }

    private fun nestedWrapperType(
        type: KSType,
        generatedPackage: String,
        wrapperPrefix: String,
        nestedObjects: MutableMap<String, MongoNestedObjectModel>,
        visiting: MutableSet<String>,
    ): com.squareup.kotlinpoet.ClassName? {
        if (type.isMarkedNullable) return null
        val declaration = type.declaration as? KSClassDeclaration ?: return null
        val qualifiedName = declaration.qualifiedName?.asString() ?: return null
        if (!shouldGenerateNestedWrapper(declaration)) return null
        val wrapperType = com.squareup.kotlinpoet.ClassName(
            generatedPackage,
            "${wrapperPrefix}${declaration.simpleName.asString().toUpperCamelIdentifier()}",
        )
        if (qualifiedName in nestedObjects) return nestedObjects.getValue(qualifiedName).wrapperType
        if (!visiting.add(qualifiedName)) {
            logger.error("recursive Mongo data class $qualifiedName is not supported by generated wrappers")
            return wrapperType
        }
        val properties = declaration.getAllProperties()
            .filterNot { it.hasAnnotation(AsteriaMongoIgnore::class.qualifiedName!!) }
            .map { property ->
                readProperty(
                    property = property,
                    generatedPackage = generatedPackage,
                    wrapperPrefix = wrapperType.simpleName,
                    nestedObjects = nestedObjects,
                    visiting = visiting,
                )
            }
            .toList()
        visiting.remove(qualifiedName)
        nestedObjects[qualifiedName] = MongoNestedObjectModel(
            sourceType = declaration.toClassName(),
            wrapperType = wrapperType,
            properties = properties,
        )
        return wrapperType
    }

    private fun shouldGenerateNestedWrapper(declaration: KSClassDeclaration): Boolean {
        return Modifier.DATA in declaration.modifiers &&
            !declaration.hasAnnotation(AsteriaMongoValue::class.qualifiedName!!)
    }

    private fun validateProperties(
        owner: KSClassDeclaration,
        properties: List<MongoEntityPropertyModel>,
    ): Boolean {
        return properties.all { property ->
            val declaration = owner.property(property.name)
            val type = declaration?.type?.resolve() ?: return@all true
            validateMongoType(type, "property ${owner.simpleName.asString()}.${property.name}", mutableSetOf())
        }
    }

    private fun validateMongoType(
        type: KSType,
        label: String,
        visiting: MutableSet<String>,
    ): Boolean {
        val declaration = type.declaration
        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName != null && qualifiedName in allowedTypes) {
            return true
        }
        if (qualifiedName in BUILTIN_MONGO_VALUE_TYPES) {
            return true
        }
        if (declaration is KSClassDeclaration) {
            if (declaration.classKind == com.google.devtools.ksp.symbol.ClassKind.ENUM_CLASS) {
                return true
            }
            if (declaration.hasAnnotation(AsteriaMongoValue::class.qualifiedName!!)) {
                return true
            }
        }
        if (qualifiedName in MAP_TYPES) {
            val keyType = type.arguments.getOrNull(0)?.type?.resolve()
            val valueType = type.arguments.getOrNull(1)?.type?.resolve()
            if (keyType == null || valueType == null) {
                logger.error("$label uses a Mongo map with unresolved key/value types")
                return false
            }
            val keyOk = validateMongoMapKeyType(keyType, "$label map key")
            val valueOk = validateMongoType(valueType, "$label map value", visiting)
            return keyOk && valueOk
        }
        if (qualifiedName in LIST_OR_SET_TYPES) {
            val elementType = type.arguments.getOrNull(0)?.type?.resolve()
            if (elementType == null) {
                logger.error("$label uses a Mongo collection with unresolved element type")
                return false
            }
            return validateMongoType(elementType, "$label element", visiting)
        }
        if (declaration is KSClassDeclaration && shouldGenerateNestedWrapper(declaration)) {
            val key = qualifiedName ?: declaration.simpleName.asString()
            if (!visiting.add(key)) {
                logger.error("$label references recursive data class $key; register it with @AsteriaMongoValue if a custom codec handles it")
                return false
            }
            val result = declaration.getAllProperties()
                .filterNot { it.hasAnnotation(AsteriaMongoIgnore::class.qualifiedName!!) }
                .all { property ->
                    validateMongoType(property.type.resolve(), "$label.${property.simpleName.asString()}", visiting)
                }
            visiting.remove(key)
            return result
        }

        logger.error(
            "$label has unsupported Mongo value type ${qualifiedName ?: declaration.simpleName.asString()}. " +
                "Use a supported scalar/collection/data class, annotate the type with @AsteriaMongoValue, or add it to " +
                "the KSP option asteria.mongodb.allowedTypes.",
        )
        return false
    }

    private fun validateMongoMapKeyType(
        type: KSType,
        label: String,
    ): Boolean {
        val qualifiedName = type.declaration.qualifiedName?.asString()
        if (qualifiedName in MONGO_MAP_KEY_TYPES || qualifiedName in allowedTypes) {
            return true
        }
        val declaration = type.declaration
        if (declaration is KSClassDeclaration && declaration.classKind == com.google.devtools.ksp.symbol.ClassKind.ENUM_CLASS) {
            return true
        }
        logger.error("$label type ${qualifiedName ?: declaration.simpleName.asString()} is not safe as a Mongo path key")
        return false
    }

    private fun propertyKind(type: KSType): MongoEntityPropertyKind {
        if (type.isMarkedNullable) return MongoEntityPropertyKind.Value
        return when (type.declaration.qualifiedName?.asString()) {
            "kotlin.collections.Map",
            "kotlin.collections.MutableMap",
            -> MongoEntityPropertyKind.Map

            "kotlin.collections.List",
            "kotlin.collections.MutableList",
            -> MongoEntityPropertyKind.List

            "kotlin.collections.Set",
            "kotlin.collections.MutableSet",
            -> MongoEntityPropertyKind.Set

            else -> MongoEntityPropertyKind.Value
        }
    }

    private fun KSClassDeclaration.property(name: String): KSPropertyDeclaration? {
        return getAllProperties().firstOrNull { it.simpleName.asString() == name }
    }

    private fun KSPropertyDeclaration.hasAnnotation(qualifiedName: String): Boolean {
        return findAnnotation(qualifiedName) != null
    }

    private fun KSClassDeclaration.hasAnnotation(qualifiedName: String): Boolean {
        return findAnnotation(qualifiedName) != null
    }

    private fun KSPropertyDeclaration.findAnnotation(qualifiedName: String): KSAnnotation? {
        return annotations.firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
        }
    }

    private fun KSClassDeclaration.findAnnotation(qualifiedName: String): KSAnnotation? {
        return annotations.firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
        }
    }

    private fun KSAnnotation.stringArg(name: String): String {
        return arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""
    }

    private companion object {
        val BUILTIN_MONGO_VALUE_TYPES = setOf(
            "kotlin.String",
            "kotlin.Boolean",
            "kotlin.Byte",
            "kotlin.Short",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Float",
            "kotlin.Double",
            "kotlin.ByteArray",
            "kotlin.IntArray",
            "kotlin.LongArray",
            "kotlin.BooleanArray",
            "kotlin.FloatArray",
            "kotlin.DoubleArray",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "org.bson.types.ObjectId",
            "org.bson.Document",
        )
        val MONGO_MAP_KEY_TYPES = setOf(
            "kotlin.String",
            "kotlin.Byte",
            "kotlin.Short",
            "kotlin.Int",
            "kotlin.Long",
        )
        val MAP_TYPES = setOf(
            "kotlin.collections.Map",
            "kotlin.collections.MutableMap",
        )
        val LIST_OR_SET_TYPES = setOf(
            "kotlin.collections.List",
            "kotlin.collections.MutableList",
            "kotlin.collections.Set",
            "kotlin.collections.MutableSet",
        )
    }
}

private fun String.toUpperCamelIdentifier(): String {
    return split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString("") { part ->
            part.replaceFirstChar { char -> char.uppercaseChar() }
        }
        .ifBlank { "Value" }
}
