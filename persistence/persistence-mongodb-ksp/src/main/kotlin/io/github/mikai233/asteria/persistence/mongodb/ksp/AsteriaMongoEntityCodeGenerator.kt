package io.github.mikai233.asteria.persistence.mongodb.ksp

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

data class MongoEntityCodegenModel(
    val packageName: String,
    val entityType: ClassName,
    val wrapperName: String,
    val helperName: String,
    val collectionName: String,
    val id: MongoEntityPropertyModel,
    val properties: List<MongoEntityPropertyModel>,
    val nestedObjects: List<MongoNestedObjectModel> = emptyList(),
)

data class MongoNestedObjectModel(
    val sourceType: ClassName,
    val wrapperType: ClassName,
    val properties: List<MongoEntityPropertyModel>,
)

data class MongoEntityPropertyModel(
    val name: String,
    val fieldName: String,
    val type: TypeName,
    val kind: MongoEntityPropertyKind = MongoEntityPropertyKind.Value,
    val trackedType: TypeName = type,
    val valueKind: MongoEntityPropertyKind = MongoEntityPropertyKind.Value,
    val scanIgnored: Boolean = false,
    val scanWholeField: Boolean = false,
)

enum class MongoEntityPropertyKind {
    Value,
    Object,
    Map,
    List,
    Set,
}

object AsteriaMongoEntityCodeGenerator {
    fun buildFile(model: MongoEntityCodegenModel): FileSpec {
        require(model.properties.none { property -> property.type.isNullableCollectionType() }) {
            "Nullable Mongo collection properties are not supported"
        }
        require(
            model.nestedObjects.none { nested ->
                nested.properties.any { property -> property.type.isNullableCollectionType() }
            },
        ) {
            "Nullable Mongo collection properties are not supported"
        }
        val wrapperType = ClassName(model.packageName, model.wrapperName)
        val helperType = ClassName(model.packageName, model.helperName)
        return FileSpec.builder(model.packageName, model.wrapperName)
            .addType(buildWrapper(model, wrapperType))
            .apply {
                model.nestedObjects.forEach { nested ->
                    addType(buildNestedWrapper(nested))
                }
            }
            .addType(buildHelper(model, helperType, wrapperType))
            .build()
    }

    private fun buildWrapper(
        model: MongoEntityCodegenModel,
        wrapperType: ClassName,
    ): TypeSpec {
        val contextType = ClassName(MONGODB_PACKAGE, "MongoTrackContext")
        val documentType = ClassName(MONGODB_PACKAGE, "MongoTrackedDocument")
            .parameterizedBy(model.id.type.copy(nullable = false), model.entityType)
        val supportType = ClassName(MONGODB_PACKAGE, "MongoTrackedObjectSupport")

        return TypeSpec.classBuilder(wrapperType)
            .addKdoc(
                "Generated dirty-tracking wrapper for [%T].\n\n" +
                        "Business code should mutate this wrapper inside the owning actor. Field writes are converted into " +
                        "Mongo patch operations by the tracking runtime.\n",
                model.entityType,
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("ctx", contextType)
                    .addParameter("entity", model.entityType)
                    .build(),
            )
            .addSuperinterface(documentType)
            .superclass(supportType)
            .addSuperclassConstructorParameter("ctx.queue")
            .addProperty(
                PropertySpec.builder("id", model.id.type.copy(nullable = false), KModifier.OVERRIDE)
                    .initializer("entity.%L", model.id.name)
                    .build(),
            )
            .apply {
                model.properties.filterNot { it.name == model.id.name }.forEach { property ->
                    addProperty(buildTrackedProperty(property))
                }
            }
            .addFunction(buildToEntity(model))
            .addFunction(buildToMongoValue(model))
            .build()
    }

    private fun buildTrackedProperty(property: MongoEntityPropertyModel): PropertySpec {
        val builder = when (property.kind) {
            MongoEntityPropertyKind.Value -> PropertySpec.builder(property.name, property.type, KModifier.PUBLIC)
                .mutable(true)
                .delegate(CodeBlock.of("ctx.trackedValue(%S, entity.%L)", property.fieldName, property.name))

            MongoEntityPropertyKind.Object -> PropertySpec.builder(
                property.name,
                property.trackedType.copy(nullable = false),
                KModifier.PUBLIC
            )
                .initializer(
                    "trackChild(%T(ctx.path(%S), entity.%L, ctx.queue, ::currentDirtyTarget))",
                    property.trackedType,
                    property.fieldName,
                    property.name,
                )

            MongoEntityPropertyKind.Map -> PropertySpec.builder(
                property.name,
                property.trackedType.copy(nullable = false).asMutableCollection(),
                KModifier.PUBLIC
            )
                .delegate(
                    CodeBlock.builder()
                        .add(
                            "%M(path = ctx.path(%S), initialValue = %L, queue = ctx.queue, ",
                            MONGO_TRACKED_MAP,
                            property.fieldName,
                            collectionInitialValueExpression(
                                property,
                                "ctx.path(${property.fieldName.toCodeString()})",
                                "entity.${property.name}",
                                "ctx.queue",
                                "::currentDirtyTarget"
                            ),
                        )
                        .add("dirtyTargetProvider = ::currentDirtyTarget)")
                        .build(),
                )

            MongoEntityPropertyKind.List -> PropertySpec.builder(
                property.name,
                property.trackedType.copy(nullable = false).asMutableCollection(),
                KModifier.PUBLIC
            )
                .delegate(
                    CodeBlock.builder()
                        .add(
                            "%M(path = ctx.path(%S), initialValue = %L, queue = ctx.queue, ",
                            MONGO_TRACKED_LIST,
                            property.fieldName,
                            collectionInitialValueExpression(
                                property,
                                "ctx.path(${property.fieldName.toCodeString()})",
                                "entity.${property.name}",
                                "ctx.queue",
                                "::currentDirtyTarget"
                            ),
                        )
                        .add("dirtyTargetProvider = ::currentDirtyTarget)")
                        .build(),
                )

            MongoEntityPropertyKind.Set -> PropertySpec.builder(
                property.name,
                property.trackedType.copy(nullable = false).asMutableCollection(),
                KModifier.PUBLIC
            )
                .delegate(
                    CodeBlock.builder()
                        .add(
                            "%M(path = ctx.path(%S), initialValue = entity.%L.toMutableSet(), queue = ctx.queue, ",
                            MONGO_TRACKED_SET,
                            property.fieldName,
                            property.name,
                        )
                        .add("dirtyTargetProvider = ::currentDirtyTarget)")
                        .build(),
                )
        }
        return builder.build()
    }

    private fun buildToEntity(model: MongoEntityCodegenModel): FunSpec {
        val arguments = model.properties.joinToString(",\n") { property ->
            val expression = when (property.kind) {
                MongoEntityPropertyKind.Value -> property.name
                MongoEntityPropertyKind.Object -> "${property.name}.toEntity()"
                MongoEntityPropertyKind.Map -> if (property.valueKind == MongoEntityPropertyKind.Object) {
                    "${property.name}.mapValues { (_, value) -> value.toEntity() }.toMutableMap()"
                } else {
                    "${property.name}.toMutableMap()"
                }

                MongoEntityPropertyKind.List -> if (property.valueKind == MongoEntityPropertyKind.Object) {
                    "${property.name}.map { it.toEntity() }.toMutableList()"
                } else {
                    "${property.name}.toMutableList()"
                }

                MongoEntityPropertyKind.Set -> "${property.name}.toMutableSet()"
            }
            "${property.name} = $expression"
        }
        return FunSpec.builder("toEntity")
            .addModifiers(KModifier.OVERRIDE)
            .returns(model.entityType)
            .addCode(
                CodeBlock.builder()
                    .add("return %T(\n", model.entityType)
                    .indent()
                    .add(arguments)
                    .unindent()
                    .add("\n)\n")
                    .build(),
            )
            .build()
    }

    private fun buildNestedWrapper(model: MongoNestedObjectModel): TypeSpec {
        val pathType = ClassName(MONGODB_PACKAGE, "MongoPath")
        val queueType = ClassName(MONGODB_PACKAGE, "MongoChangeQueue")
        val dirtyTargetType = ClassName(MONGODB_PACKAGE, "MongoDirtyTarget").copy(nullable = true)
        val supportType = ClassName(MONGODB_PACKAGE, "MongoTrackedObjectSupport")

        return TypeSpec.classBuilder(model.wrapperType)
            .addKdoc("Generated dirty-tracking wrapper for nested value [%T].\n", model.sourceType)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("path", pathType)
                    .addParameter("entity", model.sourceType)
                    .addParameter("queue", queueType)
                    .addParameter(
                        ParameterSpec.builder(
                            "dirtyTargetProvider",
                            com.squareup.kotlinpoet.LambdaTypeName.get(returnType = dirtyTargetType),
                        )
                            .defaultValue("{ null }")
                            .build(),
                    )
                    .build(),
            )
            .superclass(supportType)
            .addSuperclassConstructorParameter("queue")
            .apply {
                model.properties.forEach { property ->
                    addProperty(buildNestedTrackedProperty(property))
                }
            }
            .addFunction(
                FunSpec.builder("effectiveDirtyTarget")
                    .addModifiers(KModifier.PRIVATE)
                    .returns(dirtyTargetType)
                    .addStatement("return dirtyTargetProvider() ?: currentDirtyTarget()")
                    .build(),
            )
            .addFunction(buildNestedToEntity(model))
            .addFunction(buildNestedToMongoValue(model))
            .build()
    }

    private fun buildNestedTrackedProperty(property: MongoEntityPropertyModel): PropertySpec {
        return when (property.kind) {
            MongoEntityPropertyKind.Value -> PropertySpec.builder(property.name, property.type, KModifier.PUBLIC)
                .mutable(true)
                .delegate(
                    CodeBlock.of(
                        "%M(path.child(%S), entity.%L, queue, dirtyTarget = ::effectiveDirtyTarget)",
                        MONGO_TRACKED_VALUE,
                        property.fieldName,
                        property.name
                    )
                )
                .build()

            MongoEntityPropertyKind.Object -> PropertySpec.builder(
                property.name,
                property.trackedType.copy(nullable = false),
                KModifier.PUBLIC
            )
                .initializer(
                    "trackChild(%T(path.child(%S), entity.%L, queue, ::effectiveDirtyTarget))",
                    property.trackedType,
                    property.fieldName,
                    property.name,
                )
                .build()

            MongoEntityPropertyKind.Map -> PropertySpec.builder(
                property.name,
                property.trackedType.copy(nullable = false).asMutableCollection(),
                KModifier.PUBLIC
            )
                .delegate(
                    CodeBlock.of(
                        "%M(path = path.child(%S), initialValue = %L, queue = queue, dirtyTargetProvider = ::effectiveDirtyTarget)",
                        MONGO_TRACKED_MAP,
                        property.fieldName,
                        collectionInitialValueExpression(
                            property,
                            "path.child(${property.fieldName.toCodeString()})",
                            "entity.${property.name}",
                            "queue",
                            "::effectiveDirtyTarget"
                        ),
                    ),
                )
                .build()

            MongoEntityPropertyKind.List -> PropertySpec.builder(
                property.name,
                property.trackedType.copy(nullable = false).asMutableCollection(),
                KModifier.PUBLIC
            )
                .delegate(
                    CodeBlock.of(
                        "%M(path = path.child(%S), initialValue = %L, queue = queue, dirtyTargetProvider = ::effectiveDirtyTarget)",
                        MONGO_TRACKED_LIST,
                        property.fieldName,
                        collectionInitialValueExpression(
                            property,
                            "path.child(${property.fieldName.toCodeString()})",
                            "entity.${property.name}",
                            "queue",
                            "::effectiveDirtyTarget"
                        ),
                    ),
                )
                .build()

            MongoEntityPropertyKind.Set -> PropertySpec.builder(
                property.name,
                property.trackedType.copy(nullable = false).asMutableCollection(),
                KModifier.PUBLIC
            )
                .delegate(
                    CodeBlock.of(
                        "%M(path = path.child(%S), initialValue = entity.%L.toMutableSet(), queue = queue, dirtyTargetProvider = ::effectiveDirtyTarget)",
                        MONGO_TRACKED_SET,
                        property.fieldName,
                        property.name,
                    ),
                )
                .build()
        }
    }

    private fun buildNestedToEntity(model: MongoNestedObjectModel): FunSpec {
        val arguments = model.properties.joinToString(",\n") { property ->
            val expression = when (property.kind) {
                MongoEntityPropertyKind.Value -> property.name
                MongoEntityPropertyKind.Object -> "${property.name}.toEntity()"
                MongoEntityPropertyKind.Map -> if (property.valueKind == MongoEntityPropertyKind.Object) {
                    "${property.name}.mapValues { (_, value) -> value.toEntity() }.toMutableMap()"
                } else {
                    "${property.name}.toMutableMap()"
                }

                MongoEntityPropertyKind.List -> if (property.valueKind == MongoEntityPropertyKind.Object) {
                    "${property.name}.map { it.toEntity() }.toMutableList()"
                } else {
                    "${property.name}.toMutableList()"
                }

                MongoEntityPropertyKind.Set -> "${property.name}.toMutableSet()"
            }
            "${property.name} = $expression"
        }
        return FunSpec.builder("toEntity")
            .returns(model.sourceType)
            .addCode(
                CodeBlock.builder()
                    .add("return %T(\n", model.sourceType)
                    .indent()
                    .add(arguments)
                    .unindent()
                    .add("\n)\n")
                    .build(),
            )
            .build()
    }

    private fun buildNestedToMongoValue(model: MongoNestedObjectModel): FunSpec {
        val documentType = ClassName("org.bson", "Document")
        val entryBlock = CodeBlock.builder()
        model.properties.forEachIndexed { index, property ->
            if (index > 0) entryBlock.add(",\n")
            entryBlock.add("%S to %M(%L)", property.fieldName, MONGO_VALUE_OF, property.name)
        }
        return FunSpec.builder("toMongoValue")
            .addModifiers(KModifier.OVERRIDE)
            .returns(ANY_NULLABLE)
            .addCode(
                CodeBlock.builder()
                    .add("return %T(mapOf(\n", documentType)
                    .indent()
                    .add(entryBlock.build())
                    .unindent()
                    .add("\n))\n")
                    .build(),
            )
            .build()
    }

    private fun buildToMongoValue(model: MongoEntityCodegenModel): FunSpec {
        val documentType = ClassName("org.bson", "Document")
        val entries = buildList<CodeBlock> {
            add(CodeBlock.of("%S to %M(id)", "_id", MONGO_VALUE_OF))
            model.properties
                .filterNot { it.name == model.id.name }
                .forEach { property ->
                    add(CodeBlock.of("%S to %M(%L)", property.fieldName, MONGO_VALUE_OF, property.name))
                }
        }
        val entryBlock = CodeBlock.builder()
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                entryBlock.add(",\n")
            }
            entryBlock.add(entry)
        }
        return FunSpec.builder("toMongoValue")
            .addModifiers(KModifier.OVERRIDE)
            .returns(ANY_NULLABLE)
            .addCode(
                CodeBlock.builder()
                    .add("return %T(mapOf(\n", documentType)
                    .indent()
                    .add(entryBlock.build())
                    .unindent()
                    .add("\n))\n")
                    .build(),
            )
            .build()
    }

    private fun collectionInitialValueExpression(
        property: MongoEntityPropertyModel,
        pathExpression: String,
        sourceExpression: String,
        queueExpression: String,
        dirtyTargetExpression: String,
    ): String {
        if (property.valueKind != MongoEntityPropertyKind.Object) {
            return when (property.kind) {
                MongoEntityPropertyKind.Map -> "$sourceExpression.toMutableMap()"
                MongoEntityPropertyKind.List -> "$sourceExpression.toMutableList()"
                MongoEntityPropertyKind.Set -> "$sourceExpression.toMutableSet()"
                else -> sourceExpression
            }
        }
        val wrapper = property.collectionValueWrapperType() ?: return ""
        return when (property.kind) {
            MongoEntityPropertyKind.Map ->
                "$sourceExpression.mapValues { (key, value) -> trackChild($wrapper($pathExpression.child(key), value, $queueExpression, $dirtyTargetExpression)) }.toMutableMap()"

            MongoEntityPropertyKind.List ->
                "$sourceExpression.mapIndexed { index, value -> trackChild($wrapper($pathExpression.child(index), value, $queueExpression, $dirtyTargetExpression)) }.toMutableList()"

            else -> sourceExpression
        }
    }

    private fun buildHelper(
        model: MongoEntityCodegenModel,
        helperType: ClassName,
        wrapperType: ClassName,
    ): TypeSpec {
        val contextType = ClassName(MONGODB_PACKAGE, "MongoTrackContext")
        val databaseType = ClassName("com.mongodb.kotlin.client.coroutine", "MongoDatabase")
        val cachePolicyType = ClassName(PERSISTENCE_PACKAGE, "RowCachePolicy")
        val scanPlanType = ClassName(PERSISTENCE_PACKAGE, "EntityScanPlan").parameterizedBy(model.entityType)
        val journalType = ClassName(MONGODB_PACKAGE, "MongoWriteJournal")
        val noopJournal = ClassName(MONGODB_PACKAGE, "NoopMongoWriteJournal")
        val metricsType = ClassName("io.github.mikai233.asteria.observability", "Metrics")
        val noopMetrics = ClassName("io.github.mikai233.asteria.observability", "NoopMetrics")
        val clockType = ClassName("java.time", "Clock")
        val tableType = ClassName(MONGODB_PACKAGE, "MongoKeyedDocumentTable")
            .parameterizedBy(model.id.type.copy(nullable = false), model.entityType, wrapperType)
        val scannedTableType = ClassName(MONGODB_PACKAGE, "MongoScannedKeyedDocumentTable")
            .parameterizedBy(model.id.type.copy(nullable = false), model.entityType)

        return TypeSpec.objectBuilder(helperType)
            .addKdoc("Generated Mongo metadata and factories for [%T].\n", model.entityType)
            .addProperty(
                PropertySpec.builder("COLLECTION", STRING, KModifier.CONST)
                    .initializer("%S", model.collectionName)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("SCAN_PLAN", scanPlanType)
                    .initializer(buildScanPlanInitializer(model))
                    .build(),
            )
            .addFunction(
                FunSpec.builder("wrap")
                    .addParameter("ctx", contextType)
                    .addParameter("entity", model.entityType)
                    .returns(wrapperType)
                    .addStatement("return %T(ctx, entity)", wrapperType)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("table")
                    .addParameter("database", databaseType)
                    .addParameter("cachePolicy", cachePolicyType)
                    .addParameter(
                        ParameterSpec.builder("journal", journalType)
                            .defaultValue("%T", noopJournal)
                            .build(),
                    )
                    .addParameter(
                        ParameterSpec.builder("clock", clockType)
                            .defaultValue("%T.systemUTC()", clockType)
                            .build(),
                    )
                    .returns(tableType)
                    .addCode(
                        CodeBlock.builder()
                            .add(
                                "return object : %T(COLLECTION, %T::class, cachePolicy, database, journal, clock) {\n",
                                tableType,
                                model.entityType
                            )
                            .indent()
                            .add(
                                "override fun wrap(context: %T, entity: %T): %T = %T(context, entity)\n",
                                contextType,
                                model.entityType,
                                wrapperType,
                                wrapperType
                            )
                            .unindent()
                            .add("}\n")
                            .build(),
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("scannedTable")
                    .addParameter("database", databaseType)
                    .addParameter("cachePolicy", cachePolicyType)
                    .addParameter(
                        ParameterSpec.builder("journal", journalType)
                            .defaultValue("%T", noopJournal)
                            .build(),
                    )
                    .addParameter(
                        ParameterSpec.builder("metrics", metricsType)
                            .defaultValue("%T", noopMetrics)
                            .build(),
                    )
                    .addParameter(
                        ParameterSpec.builder("clock", clockType)
                            .defaultValue("%T.systemUTC()", clockType)
                            .build(),
                    )
                    .returns(scannedTableType)
                    .addStatement(
                        "return %T(COLLECTION, %T::class, SCAN_PLAN, cachePolicy, database, journal, metrics, clock)",
                        scannedTableType,
                        model.entityType,
                    )
                    .build(),
            )
            .apply {
                model.nestedObjects.forEach { nested ->
                    addFunction(buildNestedMongoValueFunction(nested))
                }
            }
            .build()
    }

    private fun buildScanPlanInitializer(model: MongoEntityCodegenModel): CodeBlock {
        val scannedFields = model.properties
            .filterNot { property -> property.name == model.id.name || property.scanIgnored }
            .map { property ->
                val member = when {
                    property.kind == MongoEntityPropertyKind.Map && !property.scanWholeField -> MONGO_SCANNED_MAP_FIELD
                    else -> MONGO_SCANNED_FIELD
                }
                CodeBlock.of(
                    "%M(%S) { entity: %T -> %L }",
                    member,
                    property.fieldName,
                    model.entityType,
                    scanValueExpression(property, "entity.${property.name}"),
                )
            }
        if (scannedFields.isEmpty()) {
            return CodeBlock.of("%M<%T>()", MONGO_SCAN_PLAN, model.entityType)
        }
        val builder = CodeBlock.builder()
            .add("%M(\n", MONGO_SCAN_PLAN)
            .indent()
        scannedFields.forEachIndexed { index, field ->
            if (index > 0) {
                builder.add(",\n")
            }
            builder.add(field)
        }
        return builder
            .unindent()
            .add("\n)")
            .build()
    }

    private fun buildNestedMongoValueFunction(model: MongoNestedObjectModel): FunSpec {
        val functionName = mongoValueFunctionName(model.wrapperType)
        val documentType = ClassName("org.bson", "Document")
        val entries = CodeBlock.builder()
        model.properties.forEachIndexed { index, property ->
            if (index > 0) entries.add(",\n")
            entries.add("%S to %M(%L)", property.fieldName, MONGO_VALUE_OF, scanValueExpression(property, "value.${property.name}"))
        }
        return FunSpec.builder(functionName)
            .addModifiers(KModifier.PRIVATE)
            .addParameter("value", model.sourceType)
            .returns(ANY_NULLABLE)
            .addCode(
                CodeBlock.builder()
                    .add("return %T(mapOf(\n", documentType)
                    .indent()
                    .add(entries.build())
                    .unindent()
                    .add("\n))\n")
                    .build(),
            )
            .build()
    }

    private fun scanValueExpression(property: MongoEntityPropertyModel, sourceExpression: String): CodeBlock {
        if (property.kind == MongoEntityPropertyKind.Object) {
            return CodeBlock.of("%L(%L)", mongoValueFunctionName(property.trackedType), sourceExpression)
        }
        if (property.valueKind != MongoEntityPropertyKind.Object) {
            return CodeBlock.of("%L", sourceExpression)
        }
        val wrapperType = property.collectionValueWrapperType() ?: return CodeBlock.of("%L", sourceExpression)
        val functionName = mongoValueFunctionName(wrapperType)
        return when (property.kind) {
            MongoEntityPropertyKind.Map ->
                CodeBlock.of("%L.mapValues { (_, value) -> %L(value) }", sourceExpression, functionName)

            MongoEntityPropertyKind.List ->
                CodeBlock.of("%L.map { value -> %L(value) }", sourceExpression, functionName)

            else -> CodeBlock.of("%L", sourceExpression)
        }
    }

    private fun mongoValueFunctionName(type: TypeName): String {
        val className = type as? ClassName ?: return "mongoValue"
        return className.simpleName.replaceFirstChar { it.lowercaseChar() } + "MongoValue"
    }

    private fun TypeName.asMutableCollection(): TypeName {
        val parameterized = this as? com.squareup.kotlinpoet.ParameterizedTypeName ?: return this
        val rawType = parameterized.rawType
        val mutableRawType = when (rawType.canonicalName) {
            "kotlin.collections.Map",
            "kotlin.collections.MutableMap",
                -> MUTABLE_MAP

            "kotlin.collections.List",
            "kotlin.collections.MutableList",
                -> MUTABLE_LIST

            "kotlin.collections.Set",
            "kotlin.collections.MutableSet",
                -> MUTABLE_SET

            else -> return this
        }
        return mutableRawType.parameterizedBy(parameterized.typeArguments).copy(nullable = this.isNullable)
    }

    private fun TypeName.isNullableCollectionType(): Boolean {
        if (!isNullable) return false
        val parameterized = this as? com.squareup.kotlinpoet.ParameterizedTypeName ?: return false
        return when (parameterized.rawType.canonicalName) {
            "kotlin.collections.Map",
            "kotlin.collections.MutableMap",
            "kotlin.collections.List",
            "kotlin.collections.MutableList",
            "kotlin.collections.Set",
            "kotlin.collections.MutableSet",
                -> true

            else -> false
        }
    }

    private fun MongoEntityPropertyModel.collectionValueWrapperType(): TypeName? {
        val parameterized = trackedType as? com.squareup.kotlinpoet.ParameterizedTypeName ?: return null
        return parameterized.typeArguments.lastOrNull()
    }

    private fun String.toCodeString(): String = buildString {
        append('"')
        this@toCodeString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private val ANY_NULLABLE = ClassName("kotlin", "Any").copy(nullable = true)
    private val STRING = ClassName("kotlin", "String")
    private val MUTABLE_MAP = ClassName("kotlin.collections", "MutableMap")
    private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
    private val MUTABLE_SET = ClassName("kotlin.collections", "MutableSet")
    private val MONGO_TRACKED_MAP = MemberName(MONGODB_PACKAGE, "mongoTrackedMap")
    private val MONGO_TRACKED_LIST = MemberName(MONGODB_PACKAGE, "mongoTrackedList")
    private val MONGO_TRACKED_SET = MemberName(MONGODB_PACKAGE, "mongoTrackedSet")
    private val MONGO_TRACKED_VALUE = MemberName(MONGODB_PACKAGE, "mongoTrackedValue")
    private val MONGO_VALUE_OF = MemberName(MONGODB_PACKAGE, "mongoValueOf")
    private val MONGO_SCAN_PLAN = MemberName(MONGODB_PACKAGE, "mongoScanPlan")
    private val MONGO_SCANNED_FIELD = MemberName(MONGODB_PACKAGE, "mongoScannedField")
    private val MONGO_SCANNED_MAP_FIELD = MemberName(MONGODB_PACKAGE, "mongoScannedMapField")
    private const val PERSISTENCE_PACKAGE = "io.github.mikai233.asteria.persistence"
    private const val MONGODB_PACKAGE = "io.github.mikai233.asteria.persistence.mongodb"
}
