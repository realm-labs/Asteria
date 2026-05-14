package io.github.realmlabs.asteria.persistence.mongodb.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.realmlabs.asteria.ksp.AsteriaKspDiagnostics
import io.github.realmlabs.asteria.persistence.mongodb.annotations.*
import org.bson.codecs.pojo.annotations.BsonId

/**
 * KSP entry point for generating Mongo tracked wrappers from `@AsteriaMongoEntity`.
 */
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
    private val diagnostics = AsteriaKspDiagnostics(logger, "persistence-mongodb-ksp")
    private var generated = false
    private val valueTypes: Set<String> = options["asteria.mongodb.valueTypes"]
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
        val models = mutableListOf<MongoEntityCodegenModel>()
        val sourceFiles = mutableListOf<KSFile>()
        symbols.forEach { symbol ->
            val model = readModel(symbol) ?: return@forEach
            models += model
            val file = AsteriaMongoEntityCodeGenerator.buildFile(model)
            val sourceFile = symbol.containingFile
            sourceFile?.let { sourceFiles += it }
            file.writeTo(codeGenerator, Dependencies(aggregating = false, *listOfNotNull(sourceFile).toTypedArray()))
        }
        if (models.isNotEmpty()) {
            generateCodegenSnapshot(models, sourceFiles)
        }
        generated = true
        return emptyList()
    }

    private fun generateCodegenSnapshot(
        models: List<MongoEntityCodegenModel>,
        sourceFiles: List<KSFile>,
    ) {
        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, *sourceFiles.distinctBy { it.filePath }.toTypedArray()),
            packageName = "META-INF/asteria/codegen-snapshots/persistence-mongodb",
            fileName = "entities",
            extensionName = "json",
        )
        output.bufferedWriter().use { writer ->
            writer.appendLine("{")
            writer.appendLine("  \"schemaVersion\": 1,")
            writer.appendLine("  \"kind\": \"persistence-mongodb\",")
            writer.appendLine("  \"valueTypes\": ${stringArrayJson(valueTypes.sorted())},")
            writer.appendLine("  \"entities\": [")
            models.sortedBy { it.entityType.canonicalName }.forEachIndexed { index, model ->
                writer.appendLine("    {")
                writer.appendLine("      \"entityType\": ${jsonString(model.entityType.canonicalName)},")
                writer.appendLine("      \"packageName\": ${jsonString(model.packageName)},")
                writer.appendLine("      \"collectionName\": ${jsonString(model.collectionName)},")
                writer.appendLine("      \"wrapperName\": ${jsonString(model.wrapperName)},")
                writer.appendLine("      \"helperName\": ${jsonString(model.helperName)},")
                writer.appendLine("      \"idProperty\": ${jsonString(model.id.name)},")
                writer.appendLine("      \"properties\": [")
                model.properties.sortedBy { it.name }.forEachIndexed { propertyIndex, property ->
                    appendPropertySnapshot(writer, property, indent = "        ")
                    if (propertyIndex != model.properties.lastIndex) {
                        writer.append(',')
                    }
                    writer.appendLine()
                }
                writer.appendLine("      ],")
                writer.appendLine("      \"nestedObjects\": [")
                model.nestedObjects
                    .sortedBy { it.sourceType.canonicalName }
                    .forEachIndexed { nestedIndex, nested ->
                        writer.appendLine("        {")
                        writer.appendLine("          \"sourceType\": ${jsonString(nested.sourceType.canonicalName)},")
                        writer.appendLine("          \"wrapperType\": ${jsonString(nested.wrapperType.canonicalName)},")
                        writer.appendLine("          \"properties\": [")
                        nested.properties.sortedBy { it.name }.forEachIndexed { propertyIndex, property ->
                            appendPropertySnapshot(writer, property, indent = "            ")
                            if (propertyIndex != nested.properties.lastIndex) {
                                writer.append(',')
                            }
                            writer.appendLine()
                        }
                        writer.appendLine("          ]")
                        writer.append("        }")
                        if (nestedIndex != model.nestedObjects.lastIndex) {
                            writer.append(',')
                        }
                        writer.appendLine()
                    }
                writer.appendLine("      ]")
                writer.append("    }")
                if (index != models.lastIndex) {
                    writer.append(',')
                }
                writer.appendLine()
            }
            writer.appendLine("  ]")
            writer.appendLine("}")
        }
    }

    private fun appendPropertySnapshot(
        writer: java.io.Writer,
        property: MongoEntityPropertyModel,
        indent: String,
    ) {
        writer.appendLine("${indent}{")
        writer.appendLine("$indent  \"name\": ${jsonString(property.name)},")
        writer.appendLine("$indent  \"fieldName\": ${jsonString(property.fieldName)},")
        writer.appendLine("$indent  \"type\": ${jsonString(property.type.toString())},")
        writer.appendLine("$indent  \"kind\": ${jsonString(property.kind.name)},")
        writer.appendLine("$indent  \"trackedType\": ${jsonString(property.trackedType.toString())},")
        writer.appendLine("$indent  \"valueKind\": ${jsonString(property.valueKind.name)},")
        writer.appendLine("$indent  \"scanIgnored\": ${property.scanIgnored},")
        writer.appendLine("$indent  \"scanWholeField\": ${property.scanWholeField}")
        writer.append("$indent}")
    }

    private fun readModel(symbol: KSClassDeclaration): MongoEntityCodegenModel? {
        val annotation = symbol.findAnnotation(AsteriaMongoEntity::class.qualifiedName!!) ?: return null
        val collectionName = annotation.stringArg("collection")
        if (collectionName.isBlank()) {
            diagnostics.error(
                code = "ASTERIA-MONGO-001",
                message = "@AsteriaMongoEntity collection must not be blank.",
                symbol = symbol,
                reason = "The generated repository helper needs a concrete MongoDB collection name.",
                fix = "Set collection to the MongoDB collection that stores this entity.",
            )
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
            val declaration = symbol.property(property.name)
            declaration?.hasAnnotation(AsteriaMongoId::class.qualifiedName!!) == true ||
                    declaration?.hasAnnotation(BsonId::class.qualifiedName!!) == true
        }
        val id = when {
            annotatedIds.size == 1 -> annotatedIds.single()
            annotatedIds.size > 1 -> {
                diagnostics.error(
                    code = "ASTERIA-MONGO-002",
                    message = "Only one @AsteriaMongoId or @BsonId property is allowed.",
                    symbol = symbol,
                    reason = "MongoDB has one _id field per document, and the generated wrapper maps exactly one property to it.",
                    fix = "Keep one id annotation, or remove annotations and use a single property named id.",
                )
                return null
            }

            else -> properties.singleOrNull { it.name == "id" } ?: run {
                diagnostics.error(
                    code = "ASTERIA-MONGO-003",
                    message = "Mongo entity requires an id property.",
                    symbol = symbol,
                    reason = "The generated repository and tracked wrapper need a stable entity id mapped to Mongo _id.",
                    fix = "Add a property named id, or mark exactly one property with @AsteriaMongoId or @BsonId.",
                )
                return null
            }
        }
        if (id.type.isNullable) {
            diagnostics.error(
                code = "ASTERIA-MONGO-004",
                message = "Mongo entity id must not be nullable.",
                symbol = symbol,
                reason = "The entity id is used as a repository key and Mongo _id value.",
                fix = "Change the id property type to a non-null type.",
            )
            return null
        }
        val duplicateField = properties
            .groupBy { if (it.name == id.name) "_id" else it.fieldName }
            .filterValues { it.size > 1 }
            .keys
            .firstOrNull()
        if (duplicateField != null) {
            diagnostics.error(
                code = "ASTERIA-MONGO-005",
                message = "Duplicate generated Mongo field name.",
                symbol = symbol,
                reason = "Two properties map to Mongo field $duplicateField.",
                fix = "Rename one property or use @AsteriaMongoField(name = ...) with a unique field name.",
            )
            return null
        }
        if (properties.any { it.name != id.name && it.fieldName == "_id" }) {
            diagnostics.error(
                code = "ASTERIA-MONGO-006",
                message = "Mongo field _id is reserved for the entity id.",
                symbol = symbol,
                reason = "The generated wrapper maps the selected id property to _id automatically.",
                fix = "Do not set @AsteriaMongoField(name = \"_id\") on non-id properties.",
            )
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
        val collectionNestedType =
            collectionValueWrapperType(type, generatedPackage, wrapperPrefix, nestedObjects, visiting)
        val valueKind =
            if (collectionNestedType == null) MongoEntityPropertyKind.Value else MongoEntityPropertyKind.Object
        return MongoEntityPropertyModel(
            name = property.simpleName.asString(),
            fieldName = property.findAnnotation(AsteriaMongoField::class.qualifiedName!!)
                ?.stringArg("name")
                ?.takeIf { it.isNotBlank() }
                ?: property.simpleName.asString(),
            type = type.toTypeName(),
            kind = if (nestedType == null) propertyKind(type) else MongoEntityPropertyKind.Object,
            trackedType = nestedType ?: collectionTrackedType(type, collectionNestedType) ?: type.toTypeName(),
            valueKind = valueKind,
            scanIgnored = property.hasAnnotation(AsteriaMongoScanIgnore::class.qualifiedName!!),
            scanWholeField = property.hasAnnotation(AsteriaMongoScanWholeField::class.qualifiedName!!),
        )
    }

    private fun collectionValueWrapperType(
        type: KSType,
        generatedPackage: String,
        wrapperPrefix: String,
        nestedObjects: MutableMap<String, MongoNestedObjectModel>,
        visiting: MutableSet<String>,
    ): com.squareup.kotlinpoet.ClassName? {
        if (type.isMarkedNullable) return null
        val qualifiedName = type.declaration.qualifiedName?.asString()
        if (qualifiedName !in MAP_TYPES && qualifiedName !in LIST_OR_SET_TYPES) return null
        val valueType = if (qualifiedName in MAP_TYPES) {
            type.arguments.getOrNull(1)?.type?.resolve()
        } else {
            type.arguments.getOrNull(0)?.type?.resolve()
        } ?: return null
        if (qualifiedName in SET_TYPES) {
            return null
        }
        val nestedWrapperType = nestedWrapperType(valueType, generatedPackage, wrapperPrefix, nestedObjects, visiting)
        return nestedWrapperType
    }

    private fun collectionTrackedType(
        type: KSType,
        collectionNestedType: com.squareup.kotlinpoet.ClassName?,
    ): com.squareup.kotlinpoet.TypeName? {
        collectionNestedType ?: return null
        val qualifiedName = type.declaration.qualifiedName?.asString()
        return when (qualifiedName) {
            in MAP_TYPES -> {
                val keyType = type.arguments.getOrNull(0)?.type?.resolve()?.toTypeName() ?: return null
                com.squareup.kotlinpoet.ClassName("kotlin.collections", "MutableMap")
                    .parameterizedBy(keyType, collectionNestedType)
            }

            in LIST_TYPES -> {
                com.squareup.kotlinpoet.ClassName("kotlin.collections", "MutableList")
                    .parameterizedBy(collectionNestedType)
            }

            else -> null
        }
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
            diagnostics.error(
                code = "ASTERIA-MONGO-007",
                message = "Recursive Mongo data class is not supported by generated tracked wrappers.",
                symbol = declaration,
                reason = "Generated nested wrappers expand the object graph statically and cannot represent recursive wrapper types.",
                fix = "Annotate the recursive type with @AsteriaMongoValue if a custom codec handles it, or break the recursive property graph.",
            )
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
            if (property.scanWholeField && property.kind != MongoEntityPropertyKind.Map) {
                diagnostics.error(
                    code = "ASTERIA-MONGO-008",
                    message = "@AsteriaMongoScanWholeField can only be used on Map properties.",
                    symbol = owner.property(property.name),
                    reason = "${owner.simpleName.asString()}.${property.name} is ${property.kind}, but whole-field scan is only meaningful for map path tracking.",
                    fix = "Remove @AsteriaMongoScanWholeField or change the property to a Map.",
                )
                return@all false
            }
            val declaration = owner.property(property.name)
            val type = declaration?.type?.resolve() ?: return@all true
            if (type.isNullableMongoCollectionType()) {
                diagnostics.error(
                    code = "ASTERIA-MONGO-009",
                    message = "Nullable Mongo collection properties are not supported.",
                    symbol = declaration,
                    reason = "Tracked collection wrappers need a non-null collection instance to record dirty paths.",
                    fix = "Use an empty collection, a nullable wrapper object, or a whole-value @AsteriaMongoValue type.",
                )
                return@all false
            }
            validateMongoType(type, "property ${owner.simpleName.asString()}.${property.name}", mutableSetOf())
        }
    }

    private fun validateMongoType(
        type: KSType,
        label: String,
        visiting: MutableSet<String>,
    ): Boolean {
        if (type.isNullableMongoCollectionType()) {
            diagnostics.error(
                code = "ASTERIA-MONGO-010",
                message = "Nullable Mongo collection type is not supported.",
                reason = "$label is nullable, but tracked collection wrappers require non-null instances.",
                fix = "Use an empty collection, a nullable wrapper object, or a whole-value @AsteriaMongoValue type.",
            )
            return false
        }
        val declaration = type.declaration
        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName != null && qualifiedName in valueTypes) {
            return true
        }
        if (qualifiedName in BUILTIN_MONGO_VALUE_TYPES || qualifiedName in BUILTIN_MONGO_ARRAY_TYPES) {
            return true
        }
        if (declaration is KSClassDeclaration) {
            if (declaration.classKind == ClassKind.ENUM_CLASS) {
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
                diagnostics.error(
                    code = "ASTERIA-MONGO-011",
                    message = "Mongo map has unresolved key/value types.",
                    reason = "$label uses a map type whose key or value type cannot be resolved by KSP.",
                    fix = "Use a concrete Map<K, V> or MutableMap<K, V> type.",
                )
                return false
            }
            val keyOk = validateMongoMapKeyType(keyType, "$label map key")
            val valueOk = validateMongoType(valueType, "$label map value", visiting)
            return keyOk && valueOk
        }
        if (qualifiedName in LIST_OR_SET_TYPES) {
            val elementType = type.arguments.getOrNull(0)?.type?.resolve()
            if (elementType == null) {
                diagnostics.error(
                    code = "ASTERIA-MONGO-012",
                    message = "Mongo collection has an unresolved element type.",
                    reason = "$label uses a collection type whose element type cannot be resolved by KSP.",
                    fix = "Use a concrete List<T>, MutableList<T>, Set<T>, or MutableSet<T> type.",
                )
                return false
            }
            if (qualifiedName in SET_TYPES) {
                return validateMongoSetElementType(elementType, "$label element", visiting)
            }
            return validateMongoType(elementType, "$label element", visiting)
        }
        if (declaration is KSClassDeclaration && shouldGenerateNestedWrapper(declaration)) {
            val key = qualifiedName ?: declaration.simpleName.asString()
            if (!visiting.add(key)) {
                diagnostics.error(
                    code = "ASTERIA-MONGO-013",
                    message = "Mongo tracked property references a recursive data class.",
                    reason = "$label references recursive data class $key.",
                    fix = "Annotate $key with @AsteriaMongoValue if a custom codec handles it, or break the recursive property graph.",
                )
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

        diagnostics.error(
            code = "ASTERIA-MONGO-014",
            message = "Unsupported Mongo value type.",
            reason = "$label has unsupported type ${qualifiedName ?: declaration.simpleName.asString()}.",
            fix = "Use a supported scalar, collection, enum, data class, @AsteriaMongoValue type, or add the type to KSP option asteria.mongodb.valueTypes.",
        )
        return false
    }

    private fun validateMongoSetElementType(
        type: KSType,
        label: String,
        visiting: MutableSet<String>,
    ): Boolean {
        val declaration = type.declaration
        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName in MAP_TYPES || qualifiedName in LIST_OR_SET_TYPES || qualifiedName in BUILTIN_MONGO_ARRAY_TYPES) {
            diagnostics.error(
                code = "ASTERIA-MONGO-016",
                message = "Mongo Set element type is not supported.",
                reason = "$label type ${qualifiedName ?: declaration.simpleName.asString()} contains nested collection data. Set elements must be stable whole values because Set depends on element hashCode/equals.",
                fix = "Use List or Map for nested collection data, or replace the Set element with a stable scalar/value type.",
            )
            return false
        }
        if (qualifiedName in STABLE_MONGO_VALUE_TYPES) {
            return true
        }
        if (qualifiedName != null && qualifiedName in valueTypes) {
            return true
        }
        if (declaration is KSClassDeclaration) {
            if (declaration.classKind == ClassKind.ENUM_CLASS) {
                return true
            }
            if (declaration.hasAnnotation(AsteriaMongoValue::class.qualifiedName!!)) {
                return true
            }
            if (Modifier.DATA in declaration.modifiers) {
                val result = isImmutableMongoValueClass(declaration, visiting)
                if (!result) {
                    diagnostics.error(
                        code = "ASTERIA-MONGO-017",
                        message = "Mongo Set data-class element is mutable or contains tracked nested values.",
                        reason = "$label type ${qualifiedName ?: declaration.simpleName.asString()} is not safe in Mongo Set. Set data-class elements must be immutable whole values.",
                        fix = "Use List/Map for tracked nested values, or make the Set element a val-only Mongo value type.",
                    )
                }
                return result
            }
        }
        diagnostics.error(
            code = "ASTERIA-MONGO-018",
            message = "Mongo Set element type is not stable.",
            reason = "$label type ${qualifiedName ?: declaration.simpleName.asString()} cannot be tracked safely as a Set element.",
            fix = "Use a stable scalar, enum, immutable data class, @AsteriaMongoValue type, or add the type to KSP option asteria.mongodb.valueTypes.",
        )
        return false
    }

    private fun isImmutableMongoValueClass(
        declaration: KSClassDeclaration,
        visiting: MutableSet<String>,
    ): Boolean {
        if (declaration.hasAnnotation(AsteriaMongoValue::class.qualifiedName!!)) {
            return true
        }
        if (Modifier.DATA !in declaration.modifiers) {
            return false
        }
        val key = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        if (!visiting.add(key)) {
            return false
        }
        val result = declaration.getAllProperties()
            .filterNot { it.hasAnnotation(AsteriaMongoIgnore::class.qualifiedName!!) }
            .all { property ->
                !property.isMutable && isImmutableMongoValueType(property.type.resolve(), visiting)
            }
        visiting.remove(key)
        return result
    }

    private fun isImmutableMongoValueType(
        type: KSType,
        visiting: MutableSet<String>,
    ): Boolean {
        val qualifiedName = type.declaration.qualifiedName?.asString()
        if (qualifiedName in MAP_TYPES || qualifiedName in LIST_OR_SET_TYPES || qualifiedName in BUILTIN_MONGO_ARRAY_TYPES) {
            return false
        }
        if (qualifiedName != null && qualifiedName in valueTypes) {
            return true
        }
        if (qualifiedName in STABLE_MONGO_VALUE_TYPES) {
            return true
        }
        val declaration = type.declaration as? KSClassDeclaration ?: return false
        if (declaration.classKind == ClassKind.ENUM_CLASS) {
            return true
        }
        if (declaration.hasAnnotation(AsteriaMongoValue::class.qualifiedName!!)) {
            return true
        }
        return isImmutableMongoValueClass(declaration, visiting)
    }

    private fun validateMongoMapKeyType(
        type: KSType,
        label: String,
    ): Boolean {
        val qualifiedName = type.declaration.qualifiedName?.asString()
        if (qualifiedName in MONGO_MAP_KEY_TYPES) {
            return true
        }
        val declaration = type.declaration
        if (declaration is KSClassDeclaration && declaration.classKind == ClassKind.ENUM_CLASS) {
            return true
        }
        diagnostics.error(
            code = "ASTERIA-MONGO-015",
            message = "Mongo map key type is not safe as a path key.",
            reason = "$label type ${qualifiedName ?: declaration.simpleName.asString()} cannot be converted to stable Mongo update paths.",
            fix = "Use String, numeric scalar, Boolean, Char, enum, BigInteger, or BigDecimal as the map key type.",
        )
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

    private fun KSType.isNullableMongoCollectionType(): Boolean {
        return isMarkedNullable && declaration.qualifiedName?.asString() in MAP_TYPES + LIST_OR_SET_TYPES
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

    private fun stringArrayJson(values: List<String>): String {
        return values.joinToString(prefix = "[", postfix = "]") { value -> jsonString(value) }
    }

    private fun jsonString(value: String): String {
        return buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char < ' ') {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
            append('"')
        }
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
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "org.bson.types.ObjectId",
            "org.bson.Document",
        )
        val STABLE_MONGO_VALUE_TYPES = setOf(
            "kotlin.String",
            "kotlin.Boolean",
            "kotlin.Byte",
            "kotlin.Short",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Float",
            "kotlin.Double",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "org.bson.types.ObjectId",
        )
        val BUILTIN_MONGO_ARRAY_TYPES = setOf(
            "kotlin.ByteArray",
            "kotlin.IntArray",
            "kotlin.LongArray",
            "kotlin.BooleanArray",
            "kotlin.FloatArray",
            "kotlin.DoubleArray",
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
        val LIST_TYPES = setOf(
            "kotlin.collections.List",
            "kotlin.collections.MutableList",
        )
        val SET_TYPES = setOf(
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
