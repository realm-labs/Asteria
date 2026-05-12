package io.github.realmlabs.asteria.persistence.mongodb.ksp

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

/**
 * Fully validated input used to generate one tracked document wrapper and helper object.
 */
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

/**
 * Model for a nested project data class that needs generated dirty tracking inside a parent document.
 */
data class MongoNestedObjectModel(
    val sourceType: ClassName,
    val wrapperType: ClassName,
    val properties: List<MongoEntityPropertyModel>,
)

/**
 * Property-level mapping and tracking metadata used by the Mongo wrapper generator.
 */
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

/**
 * Persistence shape recognized by the generator.
 */
enum class MongoEntityPropertyKind {
    Value,
    Object,
    Map,
    List,
    Set,
}

/**
 * Generates KotlinPoet files for tracked wrappers, nested wrappers, collection facades, and scan helpers.
 */
object AsteriaMongoEntityCodeGenerator {
    /**
     * Builds the generated Kotlin file for one Mongo entity model.
     *
     * Nullable collection properties are rejected because tracked collection delegates require a concrete mutable
     * instance to wrap.
     */
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
                model.properties.forEach { property ->
                    buildMapFacade(wrapperType, property)?.let(::addType)
                    buildListFacade(wrapperType, property)?.let(::addType)
                }
                model.nestedObjects.forEach { nested ->
                    nested.properties.forEach { property ->
                        buildMapFacade(nested.wrapperType, property)?.let(::addType)
                        buildListFacade(nested.wrapperType, property)?.let(::addType)
                    }
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
                    addProperty(buildTrackedProperty(wrapperType, property))
                }
            }
            .addFunction(buildToEntity(model))
            .addFunction(buildToMongoValue(model))
            .build()
    }

    private fun buildTrackedProperty(ownerType: ClassName, property: MongoEntityPropertyModel): PropertySpec {
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
                mapFacadeType(ownerType, property)
                    ?: property.trackedType.copy(nullable = false).asMutableCollection(),
                KModifier.PUBLIC
            )
                .apply {
                    val facadeType = mapFacadeType(ownerType, property)
                    if (facadeType != null) {
                        initializer(
                            "trackChild(%T(ctx.path(%S), entity.%L, ctx.queue, ::currentDirtyTarget))",
                            facadeType,
                            property.fieldName,
                            property.name,
                        )
                    } else {
                        delegate(
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
                    }
                }

            MongoEntityPropertyKind.List -> PropertySpec.builder(
                property.name,
                listFacadeType(ownerType, property)
                    ?: property.trackedType.copy(nullable = false).asMutableCollection(),
                KModifier.PUBLIC
            )
                .apply {
                    val facadeType = listFacadeType(ownerType, property)
                    if (facadeType != null) {
                        initializer(
                            "trackChild(%T(ctx.path(%S), entity.%L, ctx.queue, ::currentDirtyTarget))",
                            facadeType,
                            property.fieldName,
                            property.name,
                        )
                    } else {
                        delegate(
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
                    }
                }

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
                    "${property.name}.toEntityMap()"
                } else {
                    "${property.name}.toMutableMap()"
                }

                MongoEntityPropertyKind.List -> if (property.valueKind == MongoEntityPropertyKind.Object) {
                    "${property.name}.toEntityList()"
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
            .addProperty(
                PropertySpec.builder(
                    "dirtyTargetProvider",
                    LambdaTypeName.get(returnType = dirtyTargetType),
                    KModifier.PRIVATE,
                )
                    .initializer("dirtyTargetProvider")
                    .build(),
            )
            .apply {
                model.properties.forEach { property ->
                    addProperty(buildNestedTrackedProperty(model.wrapperType, property))
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

    private fun buildNestedTrackedProperty(ownerType: ClassName, property: MongoEntityPropertyModel): PropertySpec {
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
                mapFacadeType(ownerType, property)
                    ?: property.trackedType.copy(nullable = false).asMutableCollection(),
                KModifier.PUBLIC
            )
                .apply {
                    val facadeType = mapFacadeType(ownerType, property)
                    if (facadeType != null) {
                        initializer(
                            "trackChild(%T(path.child(%S), entity.%L, queue, ::effectiveDirtyTarget))",
                            facadeType,
                            property.fieldName,
                            property.name,
                        )
                    } else {
                        delegate(
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
                    }
                }
                .build()

            MongoEntityPropertyKind.List -> PropertySpec.builder(
                property.name,
                listFacadeType(ownerType, property)
                    ?: property.trackedType.copy(nullable = false).asMutableCollection(),
                KModifier.PUBLIC
            )
                .apply {
                    val facadeType = listFacadeType(ownerType, property)
                    if (facadeType != null) {
                        initializer(
                            "trackChild(%T(path.child(%S), entity.%L, queue, ::effectiveDirtyTarget))",
                            facadeType,
                            property.fieldName,
                            property.name,
                        )
                    } else {
                        delegate(
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
                    }
                }
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
                    "${property.name}.toEntityMap()"
                } else {
                    "${property.name}.toMutableMap()"
                }

                MongoEntityPropertyKind.List -> if (property.valueKind == MongoEntityPropertyKind.Object) {
                    "${property.name}.toEntityList()"
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

    private fun buildMapFacade(ownerType: ClassName, property: MongoEntityPropertyModel): TypeSpec? {
        if (property.kind != MongoEntityPropertyKind.Map || property.valueKind != MongoEntityPropertyKind.Object) {
            return null
        }
        val facadeType = mapFacadeType(ownerType, property) ?: return null
        val sourceMapType = property.type as? ParameterizedTypeName ?: return null
        val trackedMapType = property.trackedType as? ParameterizedTypeName ?: return null
        val keyType = sourceMapType.typeArguments.getOrNull(0) ?: return null
        val valueType = sourceMapType.typeArguments.getOrNull(1) ?: return null
        val trackedValueType = trackedMapType.typeArguments.getOrNull(1) ?: return null
        val pathType = ClassName(MONGODB_PACKAGE, "MongoPath")
        val queueType = ClassName(MONGODB_PACKAGE, "MongoChangeQueue")
        val dirtyTargetType = ClassName(MONGODB_PACKAGE, "MongoDirtyTarget").copy(nullable = true)
        val supportType = ClassName(MONGODB_PACKAGE, "MongoTrackedObjectSupport")
        val trackedMap = MUTABLE_MAP.parameterizedBy(keyType, trackedValueType)
        val sourceMap = MAP.parameterizedBy(keyType, valueType)
        val sourceMutableMap = MUTABLE_MAP.parameterizedBy(keyType, valueType)
        val entryType = MUTABLE_MAP.nestedClass("MutableEntry").parameterizedBy(keyType, trackedValueType)

        return TypeSpec.classBuilder(facadeType)
            .addKdoc(
                "Generated tracked map facade for [%T.%L]. Reads return tracked values; raw values can be assigned with `set`.\n",
                ownerType,
                property.name,
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("path", pathType)
                    .addParameter("initialValue", sourceMap)
                    .addParameter("queue", queueType)
                    .addParameter(
                        ParameterSpec.builder(
                            "dirtyTargetProvider",
                            LambdaTypeName.get(returnType = dirtyTargetType),
                        )
                            .defaultValue("{ null }")
                            .build(),
                    )
                    .build(),
            )
            .superclass(supportType)
            .addSuperclassConstructorParameter("queue")
            .addSuperinterface(trackedMap)
            .addProperty(
                PropertySpec.builder("path", pathType, KModifier.PRIVATE)
                    .initializer("path")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("queue", queueType, KModifier.PRIVATE)
                    .initializer("queue")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "dirtyTargetProvider",
                    LambdaTypeName.get(returnType = dirtyTargetType),
                    KModifier.PRIVATE,
                )
                    .initializer("dirtyTargetProvider")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "backing",
                    MONGO_TRACKED_MUTABLE_MAP.parameterizedBy(keyType, trackedValueType),
                    KModifier.PRIVATE,
                )
                    .initializer(
                        CodeBlock.builder()
                            .add("%T(\n", MONGO_TRACKED_MUTABLE_MAP)
                            .indent()
                            .add("path = path,\n")
                            .add("initialValue = initialValue.mapValues { (key, value) -> trackEntity(key, value) }.toMutableMap(),\n")
                            .add("queue = queue,\n")
                            .add("persistentValue = { value -> value.toMongoValue() },\n")
                            .add("trackedValue = { _, value -> trackChild(value) },\n")
                            .add("dirtyTargetProvider = ::effectiveDirtyTarget,\n")
                            .unindent()
                            .add(")")
                            .build(),
                    )
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("entries", MUTABLE_SET.parameterizedBy(entryType), KModifier.OVERRIDE)
                    .getter(FunSpec.getterBuilder().addStatement("return backing.entries").build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("keys", MUTABLE_SET.parameterizedBy(keyType), KModifier.OVERRIDE)
                    .getter(FunSpec.getterBuilder().addStatement("return backing.keys").build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "values",
                    MUTABLE_COLLECTION.parameterizedBy(trackedValueType),
                    KModifier.OVERRIDE,
                )
                    .getter(FunSpec.getterBuilder().addStatement("return backing.values").build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("size", INT, KModifier.OVERRIDE)
                    .getter(FunSpec.getterBuilder().addStatement("return backing.size").build())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("containsKey")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("key", keyType)
                    .returns(BOOLEAN)
                    .addStatement("return backing.containsKey(key)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("containsValue")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("value", trackedValueType)
                    .returns(BOOLEAN)
                    .addStatement("return backing.containsValue(value)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("get")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("key", keyType)
                    .returns(trackedValueType.copy(nullable = true))
                    .addStatement("return backing[key]")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("isEmpty")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(BOOLEAN)
                    .addStatement("return backing.isEmpty()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("clear")
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("backing.clear()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("put")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("key", keyType)
                    .addParameter("value", trackedValueType)
                    .returns(trackedValueType.copy(nullable = true))
                    .addStatement("return backing.put(key, value)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("putAll")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter(
                        "from",
                        MAP.parameterizedBy(WildcardTypeName.producerOf(keyType), trackedValueType),
                    )
                    .addStatement("backing.putAll(from)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("remove")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("key", keyType)
                    .returns(trackedValueType.copy(nullable = true))
                    .addStatement("return backing.remove(key)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("set")
                    .addModifiers(KModifier.OPERATOR)
                    .addParameter("key", keyType)
                    .addParameter("value", valueType)
                    .addStatement("backing[key] = trackEntity(key, value)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("toEntityMap")
                    .returns(sourceMutableMap)
                    .addStatement("return backing.mapValues { (_, value) -> value.toEntity() }.toMutableMap()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("toMongoValue")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(ANY_NULLABLE)
                    .addStatement("return backing.toMongoValue()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("trackEntity")
                    .addModifiers(KModifier.PRIVATE)
                    .addParameter("key", keyType)
                    .addParameter("value", valueType)
                    .returns(trackedValueType)
                    .addStatement("return %T(path.child(key), value, queue, ::effectiveDirtyTarget)", trackedValueType)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("effectiveDirtyTarget")
                    .addModifiers(KModifier.PRIVATE)
                    .returns(dirtyTargetType)
                    .addStatement("return dirtyTargetProvider() ?: currentDirtyTarget()")
                    .build(),
            )
            .build()
    }

    private fun buildListFacade(ownerType: ClassName, property: MongoEntityPropertyModel): TypeSpec? {
        if (property.kind != MongoEntityPropertyKind.List || property.valueKind != MongoEntityPropertyKind.Object) {
            return null
        }
        val facadeType = listFacadeType(ownerType, property) ?: return null
        val sourceListType = property.type as? ParameterizedTypeName ?: return null
        val trackedListType = property.trackedType as? ParameterizedTypeName ?: return null
        val valueType = sourceListType.typeArguments.getOrNull(0) ?: return null
        val trackedValueType = trackedListType.typeArguments.getOrNull(0) ?: return null
        val pathType = ClassName(MONGODB_PACKAGE, "MongoPath")
        val queueType = ClassName(MONGODB_PACKAGE, "MongoChangeQueue")
        val dirtyTargetType = ClassName(MONGODB_PACKAGE, "MongoDirtyTarget").copy(nullable = true)
        val dirtyTarget = ClassName(MONGODB_PACKAGE, "MongoDirtyTarget")
        val supportType = ClassName(MONGODB_PACKAGE, "MongoTrackedObjectSupport")
        val trackedList = MUTABLE_LIST.parameterizedBy(trackedValueType)
        val sourceList = LIST.parameterizedBy(valueType)
        val sourceMutableList = MUTABLE_LIST.parameterizedBy(valueType)

        return TypeSpec.classBuilder(facadeType)
            .addKdoc(
                "Generated tracked list facade for [%T.%L]. Reads return tracked values; raw values can be assigned or added.\n",
                ownerType,
                property.name,
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("path", pathType)
                    .addParameter("initialValue", sourceList)
                    .addParameter("queue", queueType)
                    .addParameter(
                        ParameterSpec.builder(
                            "dirtyTargetProvider",
                            LambdaTypeName.get(returnType = dirtyTargetType),
                        )
                            .defaultValue("{ null }")
                            .build(),
                    )
                    .build(),
            )
            .superclass(supportType)
            .addSuperclassConstructorParameter("queue")
            .addSuperinterface(trackedList)
            .addProperty(
                PropertySpec.builder("path", pathType, KModifier.PRIVATE)
                    .initializer("path")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("queue", queueType, KModifier.PRIVATE)
                    .initializer("queue")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "dirtyTargetProvider",
                    LambdaTypeName.get(returnType = dirtyTargetType),
                    KModifier.PRIVATE,
                )
                    .initializer("dirtyTargetProvider")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("wholeListDirty", BOOLEAN, KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("false")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "backing",
                    MONGO_TRACKED_MUTABLE_LIST.parameterizedBy(trackedValueType),
                    KModifier.PRIVATE,
                )
                    .initializer(
                        CodeBlock.builder()
                            .add("%T(\n", MONGO_TRACKED_MUTABLE_LIST)
                            .indent()
                            .add("path = path,\n")
                            .add("initialValue = initialValue.mapIndexed { index, value -> trackEntity(index, value) }.toMutableList(),\n")
                            .add("queue = queue,\n")
                            .add("persistentValue = { value -> value.toMongoValue() },\n")
                            .add("trackedValue = { _, value -> trackChild(value) },\n")
                            .add("dirtyTargetProvider = ::effectiveDirtyTarget,\n")
                            .unindent()
                            .add(")")
                            .build(),
                    )
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("size", INT, KModifier.OVERRIDE)
                    .getter(FunSpec.getterBuilder().addStatement("return backing.size").build())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("contains")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("element", trackedValueType)
                    .returns(BOOLEAN)
                    .addStatement("return backing.contains(element)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("containsAll")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("elements", COLLECTION.parameterizedBy(trackedValueType))
                    .returns(BOOLEAN)
                    .addStatement("return backing.containsAll(elements)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("get")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("index", INT)
                    .returns(trackedValueType)
                    .addStatement("return backing[index]")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("indexOf")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("element", trackedValueType)
                    .returns(INT)
                    .addStatement("return backing.indexOf(element)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("isEmpty")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(BOOLEAN)
                    .addStatement("return backing.isEmpty()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("iterator")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(MUTABLE_ITERATOR.parameterizedBy(trackedValueType))
                    .addStatement("return listIterator()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("lastIndexOf")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("element", trackedValueType)
                    .returns(INT)
                    .addStatement("return backing.lastIndexOf(element)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("listIterator")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(MUTABLE_LIST_ITERATOR.parameterizedBy(trackedValueType))
                    .addStatement("return listIterator(0)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("listIterator")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("index", INT)
                    .returns(MUTABLE_LIST_ITERATOR.parameterizedBy(trackedValueType))
                    .addCode(buildTrackedListIteratorCode(trackedValueType))
                    .build(),
            )
            .addFunction(
                FunSpec.builder("subList")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("fromIndex", INT)
                    .addParameter("toIndex", INT)
                    .returns(trackedList)
                    .addCode(buildTrackedSubListCode(facadeType, trackedValueType))
                    .build(),
            )
            .addFunction(
                FunSpec.builder("add")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("element", trackedValueType)
                    .returns(BOOLEAN)
                    .addStatement("val added = backing.add(element)")
                    .addStatement("if (added) markWholeListDirty()")
                    .addStatement("return added")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("add")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("index", INT)
                    .addParameter("element", trackedValueType)
                    .addStatement("backing.add(index, element)")
                    .addStatement("markWholeListDirty()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("addAll")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("elements", COLLECTION.parameterizedBy(trackedValueType))
                    .returns(BOOLEAN)
                    .addStatement("val added = backing.addAll(elements)")
                    .addStatement("if (added) markWholeListDirty()")
                    .addStatement("return added")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("addAll")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("index", INT)
                    .addParameter("elements", COLLECTION.parameterizedBy(trackedValueType))
                    .returns(BOOLEAN)
                    .addStatement("val added = backing.addAll(index, elements)")
                    .addStatement("if (added) markWholeListDirty()")
                    .addStatement("return added")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("clear")
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("if (backing.isEmpty()) return")
                    .addStatement("backing.clear()")
                    .addStatement("markWholeListDirty()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("remove")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("element", trackedValueType)
                    .returns(BOOLEAN)
                    .addStatement("val removed = backing.remove(element)")
                    .addStatement("if (removed) markWholeListDirty()")
                    .addStatement("return removed")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("removeAll")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("elements", COLLECTION.parameterizedBy(trackedValueType))
                    .returns(BOOLEAN)
                    .addStatement("val removed = backing.removeAll(elements)")
                    .addStatement("if (removed) markWholeListDirty()")
                    .addStatement("return removed")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("removeAt")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("index", INT)
                    .returns(trackedValueType)
                    .addStatement("val removed = backing.removeAt(index)")
                    .addStatement("markWholeListDirty()")
                    .addStatement("return removed")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("retainAll")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("elements", COLLECTION.parameterizedBy(trackedValueType))
                    .returns(BOOLEAN)
                    .addStatement("val changed = backing.retainAll(elements)")
                    .addStatement("if (changed) markWholeListDirty()")
                    .addStatement("return changed")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("set")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("index", INT)
                    .addParameter("element", trackedValueType)
                    .returns(trackedValueType)
                    .addStatement("return backing.set(index, element)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("set")
                    .addModifiers(KModifier.OPERATOR)
                    .addParameter("index", INT)
                    .addParameter("value", valueType)
                    .addStatement("backing[index] = trackEntity(index, value)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("add")
                    .addParameter("value", valueType)
                    .returns(BOOLEAN)
                    .addStatement("backing.add(trackEntity(backing.size, value))")
                    .addStatement("markWholeListDirty()")
                    .addStatement("return true")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("add")
                    .addParameter("index", INT)
                    .addParameter("value", valueType)
                    .addStatement("backing.add(index, trackEntity(index, value))")
                    .addStatement("markWholeListDirty()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("toEntityList")
                    .returns(sourceMutableList)
                    .addStatement("return backing.map { value -> value.toEntity() }.toMutableList()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("toMongoValue")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(ANY_NULLABLE)
                    .addStatement("return backing.toMongoValue()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("trackEntity")
                    .addModifiers(KModifier.PRIVATE)
                    .addParameter("index", INT)
                    .addParameter("value", valueType)
                    .returns(trackedValueType)
                    .addStatement(
                        "return %T(path.child(index), value, queue, ::effectiveDirtyTarget)",
                        trackedValueType
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("markWholeListDirty")
                    .addModifiers(KModifier.PRIVATE)
                    .addStatement("wholeListDirty = true")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("effectiveDirtyTarget")
                    .addModifiers(KModifier.PRIVATE)
                    .returns(dirtyTargetType)
                    .addStatement(
                        "return dirtyTargetProvider() ?: currentDirtyTarget() ?: if (wholeListDirty) %T(path, this) else null",
                        dirtyTarget,
                    )
                    .build(),
            )
            .build()
    }

    private fun buildTrackedListIteratorCode(trackedValueType: TypeName): CodeBlock {
        return CodeBlock.builder()
            .addStatement("val iterator = backing.listIterator(index)")
            .add("return object : %T<%T> {\n", MUTABLE_LIST_ITERATOR, trackedValueType)
            .indent()
            .add("override fun add(element: %T) {\n", trackedValueType)
            .indent()
            .addStatement("iterator.add(element)")
            .addStatement("markWholeListDirty()")
            .unindent()
            .add("}\n\n")
            .addStatement("override fun hasNext(): Boolean = iterator.hasNext()")
            .addStatement("override fun hasPrevious(): Boolean = iterator.hasPrevious()")
            .addStatement("override fun next(): %T = iterator.next()", trackedValueType)
            .addStatement("override fun nextIndex(): Int = iterator.nextIndex()")
            .addStatement("override fun previous(): %T = iterator.previous()", trackedValueType)
            .addStatement("override fun previousIndex(): Int = iterator.previousIndex()")
            .add("\n")
            .add("override fun remove() {\n")
            .indent()
            .addStatement("iterator.remove()")
            .addStatement("markWholeListDirty()")
            .unindent()
            .add("}\n\n")
            .add("override fun set(element: %T) {\n", trackedValueType)
            .indent()
            .addStatement("iterator.set(element)")
            .unindent()
            .add("}\n")
            .unindent()
            .add("}\n")
            .build()
    }

    private fun buildTrackedSubListCode(
        facadeType: ClassName,
        trackedValueType: TypeName,
    ): CodeBlock {
        return CodeBlock.builder()
            .addStatement("var endExclusive = toIndex")
            .add("return object : %T<%T>() {\n", ABSTRACT_MUTABLE_LIST, trackedValueType)
            .indent()
            .add("override val size: Int\n")
            .indent()
            .addStatement("get() = endExclusive - fromIndex")
            .unindent()
            .add("\n")
            .addStatement(
                "override fun get(index: Int): %T = this@%L[fromIndex + index]",
                trackedValueType,
                facadeType.simpleName
            )
            .add("\n")
            .add("override fun set(index: Int, element: %T): %T {\n", trackedValueType, trackedValueType)
            .indent()
            .addStatement("return this@%L.set(fromIndex + index, element)", facadeType.simpleName)
            .unindent()
            .add("}\n\n")
            .add("override fun add(index: Int, element: %T) {\n", trackedValueType)
            .indent()
            .addStatement("this@%L.add(fromIndex + index, element)", facadeType.simpleName)
            .addStatement("endExclusive++")
            .unindent()
            .add("}\n\n")
            .add("override fun removeAt(index: Int): %T {\n", trackedValueType)
            .indent()
            .addStatement("val removed = this@%L.removeAt(fromIndex + index)", facadeType.simpleName)
            .addStatement("endExclusive--")
            .addStatement("return removed")
            .unindent()
            .add("}\n")
            .unindent()
            .add("}\n")
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
        val metricsType = ClassName("io.github.realmlabs.asteria.observability", "Metrics")
        val noopMetrics = ClassName("io.github.realmlabs.asteria.observability", "NoopMetrics")
        val clockType = ClassName("kotlin.time", "Clock")
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
                            .defaultValue("%T.System", clockType)
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
                            .defaultValue("%T.System", clockType)
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
            entries.add(
                "%S to %M(%L)",
                property.fieldName,
                MONGO_VALUE_OF,
                scanValueExpression(property, "value.${property.name}")
            )
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

    private fun mapFacadeType(ownerType: ClassName, property: MongoEntityPropertyModel): ClassName? {
        if (property.kind != MongoEntityPropertyKind.Map || property.valueKind != MongoEntityPropertyKind.Object) {
            return null
        }
        return ClassName(ownerType.packageName, "${ownerType.simpleName}${property.name.toUpperCamelIdentifier()}Map")
    }

    private fun listFacadeType(ownerType: ClassName, property: MongoEntityPropertyModel): ClassName? {
        if (property.kind != MongoEntityPropertyKind.List || property.valueKind != MongoEntityPropertyKind.Object) {
            return null
        }
        return ClassName(ownerType.packageName, "${ownerType.simpleName}${property.name.toUpperCamelIdentifier()}List")
    }

    private fun String.toUpperCamelIdentifier(): String {
        return split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
            .joinToString("") { part ->
                part.replaceFirstChar { char -> char.uppercaseChar() }
            }
            .ifBlank { "Value" }
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
    private val BOOLEAN = ClassName("kotlin", "Boolean")
    private val INT = ClassName("kotlin", "Int")
    private val STRING = ClassName("kotlin", "String")
    private val ABSTRACT_MUTABLE_LIST = ClassName("kotlin.collections", "AbstractMutableList")
    private val COLLECTION = ClassName("kotlin.collections", "Collection")
    private val LIST = ClassName("kotlin.collections", "List")
    private val MAP = ClassName("kotlin.collections", "Map")
    private val MUTABLE_COLLECTION = ClassName("kotlin.collections", "MutableCollection")
    private val MUTABLE_ITERATOR = ClassName("kotlin.collections", "MutableIterator")
    private val MUTABLE_LIST_ITERATOR = ClassName("kotlin.collections", "MutableListIterator")
    private val MUTABLE_MAP = ClassName("kotlin.collections", "MutableMap")
    private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
    private val MUTABLE_SET = ClassName("kotlin.collections", "MutableSet")
    private val MONGO_TRACKED_MUTABLE_MAP = ClassName(MONGODB_PACKAGE, "MongoTrackedMutableMap")
    private val MONGO_TRACKED_MUTABLE_LIST = ClassName(MONGODB_PACKAGE, "MongoTrackedMutableList")
    private val MONGO_TRACKED_MAP = MemberName(MONGODB_PACKAGE, "mongoTrackedMap")
    private val MONGO_TRACKED_LIST = MemberName(MONGODB_PACKAGE, "mongoTrackedList")
    private val MONGO_TRACKED_SET = MemberName(MONGODB_PACKAGE, "mongoTrackedSet")
    private val MONGO_TRACKED_VALUE = MemberName(MONGODB_PACKAGE, "mongoTrackedValue")
    private val MONGO_VALUE_OF = MemberName(MONGODB_PACKAGE, "mongoValueOf")
    private val MONGO_SCAN_PLAN = MemberName(MONGODB_PACKAGE, "mongoScanPlan")
    private val MONGO_SCANNED_FIELD = MemberName(MONGODB_PACKAGE, "mongoScannedField")
    private val MONGO_SCANNED_MAP_FIELD = MemberName(MONGODB_PACKAGE, "mongoScannedMapField")
    private const val PERSISTENCE_PACKAGE = "io.github.realmlabs.asteria.persistence"
    private const val MONGODB_PACKAGE = "io.github.realmlabs.asteria.persistence.mongodb"
}
