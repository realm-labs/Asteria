package io.github.realmlabs.asteria.event.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.realmlabs.asteria.ksp.*
import java.util.*

class AsteriaEventHandlerSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AsteriaEventHandlerSymbolProcessor(environment.codeGenerator, environment.logger)
    }
}

private data class EventHandlerBinding(
    val rootPackage: String,
    val contextTypeName: TypeName,
    val handler: KSClassDeclaration,
    val eventDeclaration: KSClassDeclaration,
    val eventClassName: ClassName,
    val dispatcher: String,
    val topics: List<String>,
    val order: Int,
    val sourceFile: KSFile,
)

private data class EventTopicBinding(
    val rootPackage: String,
    val declaration: KSClassDeclaration,
    val path: String,
    val parent: KSClassDeclaration?,
    val sourceFile: KSFile,
)

private class AsteriaEventHandlerSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private val diagnostics = AsteriaKspDiagnostics(logger, "event-ksp")
    private val eventHandlerConstructors = AsteriaKspConstructors(
        diagnostics = diagnostics,
        codePrefix = "ASTERIA-EVENT",
        annotationName = "@AsteriaEventHandler",
    )
    private var generated = false
    private var pendingDeferred: List<AsteriaKspDeferredSymbol> = emptyList()
    private val eventHandlerAnnotationName = "io.github.realmlabs.asteria.event.AsteriaEventHandler"
    private val eventTopicRootAnnotationName = "io.github.realmlabs.asteria.event.AsteriaEventTopicRoot"
    private val eventTopicAnnotationName = "io.github.realmlabs.asteria.event.AsteriaEventTopic"
    private val gameEventName = "io.github.realmlabs.asteria.event.GameEvent"
    private val eventPublisherName = "io.github.realmlabs.asteria.event.EventPublisher"

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) {
            return emptyList()
        }
        val topicBindings = when (val result = collectTopicBindings(resolver)) {
            is AsteriaKspSymbolRead.Success -> result.value
            is AsteriaKspSymbolRead.Deferred -> {
                pendingDeferred = listOf(result.symbol)
                return listOf(result.symbol.symbol)
            }

            AsteriaKspSymbolRead.Invalid -> emptyList()
        }
        topicBindings.groupBy(EventTopicBinding::rootPackage).forEach { (rootPackage, rootTopics) ->
            generateTopicPaths(rootPackage, rootTopics)
        }
        val topicPathsByClass = topicBindings.associateBy(
            { requireNotNull(it.declaration.qualifiedName).asString() },
            EventTopicBinding::path,
        )
        val handlerDeclarationReads = resolver.getSymbolsWithAnnotation(eventHandlerAnnotationName)
            .map { symbol ->
                symbol.asAnnotatedClassOrInvalid(
                    diagnostics = diagnostics,
                    code = "ASTERIA-EVENT-014",
                    annotationName = "@AsteriaEventHandler",
                )
            }
            .toList()
        val declarationDeferred = handlerDeclarationReads.deferredSymbols()
        if (declarationDeferred.isNotEmpty()) {
            pendingDeferred = declarationDeferred
            return declarationDeferred.map { it.symbol }
        }
        val handlerReads = handlerDeclarationReads.successfulValues().map { declaration ->
            readOrDefer(declaration, "event handler ${declaration.qualifiedName?.asString()}") {
                declaration.toEventHandlerBinding(
                    declaration.requiredContainingFile("@AsteriaEventHandler") ?: return@readOrDefer null,
                    topicPathsByClass,
                )
            }
        }
        val handlerDeferred = handlerReads.deferredSymbols()
        if (handlerDeferred.isNotEmpty()) {
            pendingDeferred = handlerDeferred
            return handlerDeferred.map { it.symbol }
        }
        val bindings = handlerReads.successfulValues()
        bindings.groupBy(EventHandlerBinding::rootPackage).forEach { (rootPackage, rootBindings) ->
            generateDispatchers(rootPackage, rootBindings)
        }
        val topicsByRootPackage = topicBindings.groupBy(EventTopicBinding::rootPackage)
        val handlersByRootPackage = bindings.groupBy(EventHandlerBinding::rootPackage)
        (topicsByRootPackage.keys + handlersByRootPackage.keys).sorted().forEach { rootPackage ->
            generateCodegenSnapshot(
                rootPackage = rootPackage,
                topicBindings = topicsByRootPackage[rootPackage].orEmpty(),
                handlerBindings = handlersByRootPackage[rootPackage].orEmpty(),
            )
        }
        generated = true
        pendingDeferred = emptyList()
        return emptyList()
    }

    override fun finish() {
        diagnostics.reportUnprocessedDeferredSymbols(
            code = "ASTERIA-EVENT-015",
            message = "Event KSP symbol could not be processed after KSP rounds completed.",
            deferred = pendingDeferred,
            fix = "Check that annotated event topics, handlers, and handle parameter types are resolvable in this module.",
        )
    }

    private fun <T : Any> readOrDefer(
        symbol: KSAnnotated,
        label: String,
        read: () -> T?,
    ): AsteriaKspSymbolRead<T> {
        return try {
            read()?.let { AsteriaKspSymbolRead.Success(it) } ?: AsteriaKspSymbolRead.Invalid
        } catch (error: Throwable) {
            AsteriaKspSymbolRead.Deferred(
                AsteriaKspDeferredSymbol(
                    symbol = symbol,
                    reason = "$label could not be resolved in this KSP round: ${error.message ?: error::class.simpleName}",
                ),
            )
        }
    }

    private fun collectTopicBindings(resolver: Resolver): AsteriaKspSymbolRead<List<EventTopicBinding>> {
        val rootReads = resolver.getSymbolsWithAnnotation(eventTopicRootAnnotationName)
            .map { symbol ->
                symbol.asAnnotatedClassOrInvalid(
                    diagnostics = diagnostics,
                    code = "ASTERIA-EVENT-016",
                    annotationName = "@AsteriaEventTopicRoot",
                    expectedTarget = "object",
                )
            }
            .toList()
        val topicReads = resolver.getSymbolsWithAnnotation(eventTopicAnnotationName)
            .map { symbol ->
                symbol.asAnnotatedClassOrInvalid(
                    diagnostics = diagnostics,
                    code = "ASTERIA-EVENT-017",
                    annotationName = "@AsteriaEventTopic",
                    expectedTarget = "object",
                )
            }
            .toList()
        val deferred = (rootReads + topicReads).deferredSymbols()
        if (deferred.isNotEmpty()) {
            return AsteriaKspSymbolRead.Deferred(deferred.first())
        }
        val declarations = (rootReads.successfulValues() + topicReads.successfulValues())
            .associateBy { it.qualifiedName?.asString().orEmpty() }
        val cache = linkedMapOf<String, EventTopicBinding>()
        declarations.values.forEach { declaration ->
            when (val read = readOrDefer(declaration, "event topic ${declaration.qualifiedName?.asString()}") {
                declaration.toTopicBinding(declarations, cache)
            }) {
                is AsteriaKspSymbolRead.Success -> Unit
                is AsteriaKspSymbolRead.Deferred -> return read
                AsteriaKspSymbolRead.Invalid -> Unit
            }
        }
        return AsteriaKspSymbolRead.Success(cache.values.toList())
    }

    private fun KSClassDeclaration.toTopicBinding(
        declarations: Map<String, KSClassDeclaration>,
        cache: MutableMap<String, EventTopicBinding>,
    ): EventTopicBinding? {
        val qualifiedName = qualifiedName?.asString() ?: run {
            diagnostics.error(
                code = "ASTERIA-EVENT-021",
                message = "Event topic declaration must have a qualified name.",
                symbol = this,
                reason = "Generated topic paths and snapshots need stable qualified object names.",
                fix = "Move the annotated topic object to a named package-level or nested object declaration.",
            )
            return null
        }
        cache[qualifiedName]?.let { return it }
        if (classKind != ClassKind.OBJECT) {
            diagnostics.error(
                code = "ASTERIA-EVENT-001",
                message = "Event topic declaration must be an object.",
                symbol = this,
                reason = "Topic references are resolved from stable singleton declarations during KSP processing.",
                fix = "Move @AsteriaEventTopicRoot or @AsteriaEventTopic to an object declaration.",
            )
            return null
        }
        val rootAnnotation = findAnnotation(eventTopicRootAnnotationName)
        val topicAnnotation = findAnnotation(eventTopicAnnotationName)
        if (rootAnnotation == null && topicAnnotation == null) {
            diagnostics.error(
                code = "ASTERIA-EVENT-020",
                message = "Event topic annotation could not be resolved.",
                symbol = this,
                reason = "KSP returned the symbol for a topic annotation, but the processor could not read the annotation instance.",
                fix = "Check that the event annotation classes are available on the KSP classpath.",
            )
            return null
        }
        if (rootAnnotation != null && topicAnnotation != null) {
            diagnostics.error(
                code = "ASTERIA-EVENT-002",
                message = "Event topic declaration cannot use both root and child topic annotations.",
                symbol = this,
                reason = "@AsteriaEventTopicRoot starts a topic tree; @AsteriaEventTopic declares a child under another topic.",
                fix = "Keep only one of @AsteriaEventTopicRoot or @AsteriaEventTopic on this object.",
            )
            return null
        }
        val segment = (rootAnnotation ?: topicAnnotation)?.stringArg("value")
            ?.ifBlank { simpleName.asString().toTopicSegment() }
            ?: return null
        if (!segment.isValidEventTopicSegment()) {
            diagnostics.error(
                code = "ASTERIA-EVENT-003",
                message = "Event topic segment is invalid.",
                symbol = this,
                reason = "Topic segments must be non-blank and contain only letters, digits, '-' or '_'.",
                fix = "Change the annotation value or object name to a valid topic segment.",
            )
            return null
        }
        val parentDeclaration = parentDeclaration as? KSClassDeclaration
        val parentBinding = if (rootAnnotation == null) {
            val parentName = parentDeclaration?.qualifiedName?.asString()
            val parent = parentName?.let(declarations::get)
            if (parent == null) {
                diagnostics.error(
                    code = "ASTERIA-EVENT-004",
                    message = "Event topic child must be nested under another event topic declaration.",
                    symbol = this,
                    reason = "KSP builds child topic paths from the parent annotated topic object.",
                    fix = "Nest this object inside an @AsteriaEventTopicRoot or @AsteriaEventTopic object.",
                )
                return null
            }
            parent.toTopicBinding(declarations, cache) ?: return null
        } else {
            null
        }
        val path = parentBinding?.let { "${it.path}.$segment" } ?: segment
        val binding = EventTopicBinding(
            rootPackage = packageName.asString().substringBefore(".event.", packageName.asString()),
            declaration = this,
            path = path,
            parent = parentDeclaration,
            sourceFile = requiredContainingFile("@AsteriaEventTopic") ?: return null,
        )
        cache[qualifiedName] = binding
        return binding
    }

    private fun KSClassDeclaration.toEventHandlerBinding(
        sourceFile: KSFile,
        topicPathsByClass: Map<String, String>,
    ): EventHandlerBinding? {
        if (classKind != ClassKind.CLASS || Modifier.ABSTRACT in modifiers) {
            diagnostics.error(
                code = "ASTERIA-EVENT-005",
                message = "@AsteriaEventHandler must target a concrete class.",
                symbol = this,
                reason = "The generated event registry instantiates handler classes when it builds handles.",
                fix = "Move the annotation to a public concrete class.",
            )
            return null
        }
        val handleFunction = getDeclaredFunctions().singleOrNull {
            it.simpleName.asString() == "handle" && it.parameters.size == 3
        } ?: run {
            diagnostics.error(
                code = "ASTERIA-EVENT-006",
                message = "@AsteriaEventHandler must define exactly one handle(context, event, publisher) function.",
                symbol = this,
                reason = "The generated dispatcher infers context and event types from this function signature.",
                fix = "Add one function shaped like fun handle(context: C, event: E, publisher: EventPublisher<C>).",
            )
            return null
        }
        val contextType = handleFunction.parameters[0].type.toTypeName()
        val eventDeclaration = handleFunction.parameters[1].type.resolve().targetClassDeclaration()
            ?: run {
                diagnostics.error(
                    code = "ASTERIA-EVENT-007",
                    message = "Event handler event parameter must resolve to a class type.",
                    symbol = handleFunction.parameters[1],
                    reason = "The generated registry stores event KClass references.",
                    fix = "Use a concrete GameEvent class as the second handle parameter.",
                )
                return null
            }
        if (!eventDeclaration.isSubtypeOf(gameEventName)) {
            diagnostics.error(
                code = "ASTERIA-EVENT-008",
                message = "Event handler event parameter must implement GameEvent.",
                symbol = handleFunction.parameters[1],
                reason = "Asteria event dispatch only routes types that implement GameEvent.",
                fix = "Make the event type implement GameEvent, or use GameEvent for topic subscriptions.",
            )
            return null
        }
        val publisherDeclaration = handleFunction.parameters[2].type.resolve().declaration as? KSClassDeclaration
        if (publisherDeclaration?.qualifiedName?.asString() != eventPublisherName) {
            diagnostics.error(
                code = "ASTERIA-EVENT-009",
                message = "Event handler publisher parameter must be EventPublisher<C>.",
                symbol = handleFunction.parameters[2],
                reason = "Generated event handles pass the dispatcher publisher through the third handle parameter.",
                fix = "Use EventPublisher with the same context type as the first handle parameter.",
            )
            return null
        }
        val annotation = findAnnotation(eventHandlerAnnotationName) ?: run {
            diagnostics.error(
                code = "ASTERIA-EVENT-018",
                message = "@AsteriaEventHandler annotation could not be resolved.",
                symbol = this,
                reason = "KSP returned the symbol for the annotation, but the processor could not read the annotation instance.",
                fix = "Check that the annotation class is available on the KSP classpath.",
            )
            return null
        }
        val topicRefPaths = annotation.classListArg("topicRefs").mapNotNull { topicRef ->
            val topicPath = topicPathsByClass[topicRef.qualifiedName?.asString()]
            if (topicPath == null) {
                diagnostics.error(
                    code = "ASTERIA-EVENT-010",
                    message = "Event handler topicRefs must reference annotated topic objects.",
                    symbol = this,
                    reason = "KSP can only convert topicRefs to paths when the referenced object has @AsteriaEventTopicRoot or @AsteriaEventTopic.",
                    fix = "Annotate the referenced topic object, or use the topics string list for manually maintained paths.",
                )
            }
            topicPath
        }
        val topics = (annotation.stringListArg("topics") + topicRefPaths).distinct()
        if (topics.any { !it.isValidEventTopicPath() }) {
            diagnostics.error(
                code = "ASTERIA-EVENT-011",
                message = "Event handler topics must be non-blank dot-separated topic paths.",
                symbol = this,
                reason = "Topic paths are split into non-empty segments and matched at runtime.",
                fix = "Use topicRefs when possible, or use strings such as player.login or world_tick.",
            )
            return null
        }
        if (topics.isNotEmpty() && eventDeclaration.qualifiedName?.asString() != gameEventName) {
            diagnostics.error(
                code = "ASTERIA-EVENT-012",
                message = "Topic event handlers must accept GameEvent as the event parameter.",
                symbol = handleFunction.parameters[1],
                reason = "A topic subscription can receive different GameEvent implementations on the same topic.",
                fix = "Change the second handle parameter to GameEvent, or remove topics/topicRefs and subscribe by event type.",
            )
            return null
        }
        val packageName = packageName.asString()
        if (!eventHandlerConstructors.validateConstructible(this)) {
            return null
        }
        return EventHandlerBinding(
            rootPackage = packageName.substringBefore(".handler.", packageName),
            contextTypeName = contextType,
            handler = this,
            eventDeclaration = eventDeclaration,
            eventClassName = eventDeclaration.toClassName(),
            dispatcher = annotation.stringArg("dispatcher").ifBlank { "default" },
            topics = topics,
            order = annotation.intArg("order"),
            sourceFile = sourceFile,
        )
    }

    private fun KSClassDeclaration.requiredContainingFile(annotationName: String): KSFile? {
        val file = containingFile
        if (file != null) {
            return file
        }
        diagnostics.error(
            code = "ASTERIA-EVENT-019",
            message = "$annotationName declaration must come from a source file.",
            symbol = this,
            reason = "Generated output needs source-file dependencies so KSP incremental builds cannot silently miss annotated declarations.",
            fix = "Move the annotation to a source declaration in this module.",
        )
        return null
    }

    private fun generateTopicPaths(rootPackage: String, bindings: List<EventTopicBinding>) {
        val generatedPackage = "$rootPackage.generated"
        val moduleName = rootPackage.substringAfterLast('.').toUpperCamel()
        val rootBindings = bindings.filter { it.parent == null }.sortedBy { it.path }
        val generatedType = TypeSpec.objectBuilder("Generated${moduleName}EventTopicPaths")
            .apply {
                rootBindings.forEach { root ->
                    addProperty(root.constantProperty())
                    addType(root.topicPathObject(bindings))
                }
            }
            .build()
        FileSpec.builder(generatedPackage, "Generated${moduleName}EventTopicPaths")
            .addType(generatedType)
            .build()
            .writeTo(
                codeGenerator,
                Dependencies(aggregating = false, *bindings.map(EventTopicBinding::sourceFile).toTypedArray()),
            )
    }

    private fun EventTopicBinding.topicPathObject(bindings: List<EventTopicBinding>): TypeSpec {
        val children = bindings
            .filter { it.parent?.qualifiedName?.asString() == declaration.qualifiedName?.asString() }
            .sortedBy { it.path }
        return TypeSpec.objectBuilder(declaration.simpleName.asString())
            .addProperty(topicProperty())
            .apply {
                children.forEach { child ->
                    addProperty(child.constantProperty())
                    addType(child.topicPathObject(bindings))
                }
            }
            .build()
    }

    private fun EventTopicBinding.topicProperty(): PropertySpec {
        return PropertySpec.builder("TOPIC", String::class, KModifier.CONST)
            .initializer("%S", path)
            .build()
    }

    private fun EventTopicBinding.constantProperty(): PropertySpec {
        return PropertySpec.builder(declaration.simpleName.asString().toUpperSnake(), String::class, KModifier.CONST)
            .initializer("%S", path)
            .build()
    }

    private fun generateDispatchers(rootPackage: String, bindings: List<EventHandlerBinding>) {
        val generatedPackage = "$rootPackage.generated"
        val moduleName = rootPackage.substringAfterLast('.').toUpperCamel()
        val dispatchers = bindings.map { it.dispatcher }.distinct().sorted()
        dispatchers.forEach { dispatcherKey ->
            val dispatcherBindings = bindings.filter { it.dispatcher == dispatcherKey }
            generateEventHandles(rootPackage, moduleName, dispatcherKey, dispatcherBindings)
        }
        val generatedType = TypeSpec.objectBuilder("Generated${moduleName}EventDispatchers")
            .apply {
                dispatchers.forEach { dispatcherKey ->
                    val dispatcherBindings = bindings.filter { it.dispatcher == dispatcherKey }
                    val contextType =
                        sharedContextType(rootPackage, dispatcherKey, dispatcherBindings) ?: return@forEach
                    addProperty(
                        PropertySpec.builder(
                            dispatcherKey.toRegistryPropertyName(),
                            DEFAULT_EVENT_HANDLE_REGISTRY.parameterizedBy(contextType),
                        )
                            .initializer(buildRegistryExpression(rootPackage, moduleName, dispatcherKey, contextType))
                            .build(),
                    )
                    addProperty(
                        PropertySpec.builder(
                            dispatcherKey.toDispatcherPropertyName(),
                            EVENT_DISPATCHER.parameterizedBy(contextType),
                        )
                            .initializer("%T(%N)", EVENT_DISPATCHER, dispatcherKey.toRegistryPropertyName())
                            .build(),
                    )
                }
            }
            .build()
        FileSpec.builder(generatedPackage, "Generated${moduleName}EventDispatchers")
            .addType(generatedType)
            .build()
            .writeTo(
                codeGenerator,
                Dependencies(aggregating = false, *bindings.map(EventHandlerBinding::sourceFile).toTypedArray()),
            )
    }

    private fun generateCodegenSnapshot(
        rootPackage: String,
        topicBindings: List<EventTopicBinding>,
        handlerBindings: List<EventHandlerBinding>,
    ) {
        val sourceFiles = (topicBindings.map(EventTopicBinding::sourceFile) +
                handlerBindings.map(EventHandlerBinding::sourceFile))
            .distinctBy { it.filePath }
            .toTypedArray()
        val generatedPackage = "$rootPackage.generated"
        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, *sourceFiles),
            packageName = "META-INF/asteria/codegen-snapshots/event",
            fileName = rootPackage.toSnapshotFileName(),
            extensionName = "json",
        )
        output.bufferedWriter().use { writer ->
            writer.appendLine("{")
            writer.appendLine("  \"schemaVersion\": 1,")
            writer.appendLine("  \"kind\": \"event\",")
            writer.appendLine("  \"rootPackage\": ${jsonString(rootPackage)},")
            writer.appendLine("  \"generatedPackage\": ${jsonString(generatedPackage)},")
            writer.appendLine("  \"topics\": [")
            topicBindings.sortedBy { it.path }.forEachIndexed { index, binding ->
                writer.appendLine("    {")
                writer.appendLine("      \"path\": ${jsonString(binding.path)},")
                writer.appendLine("      \"declarationType\": ${jsonString(binding.declaration.toClassName().canonicalName)},")
                if (binding.parent == null) {
                    writer.appendLine("      \"parentType\": null")
                } else {
                    writer.appendLine("      \"parentType\": ${jsonString(binding.parent.toClassName().canonicalName)}")
                }
                writer.append("    }")
                if (index != topicBindings.lastIndex) {
                    writer.append(',')
                }
                writer.appendLine()
            }
            writer.appendLine("  ],")
            writer.appendLine("  \"handlers\": [")
            handlerBindings
                .sortedWith(compareBy({ it.dispatcher }, { it.eventClassName.canonicalName }, { it.order }))
                .forEachIndexed { index, binding ->
                    writer.appendLine("    {")
                    writer.appendLine("      \"dispatcher\": ${jsonString(binding.dispatcher)},")
                    writer.appendLine("      \"contextType\": ${jsonString(binding.contextTypeName.toString())},")
                    writer.appendLine("      \"eventType\": ${jsonString(binding.eventClassName.canonicalName)},")
                    writer.appendLine("      \"handlerType\": ${jsonString(binding.handler.toClassName().canonicalName)},")
                    writer.appendLine("      \"topics\": ${stringArrayJson(binding.topics)},")
                    writer.appendLine("      \"order\": ${binding.order}")
                    writer.append("    }")
                    if (index != handlerBindings.lastIndex) {
                        writer.append(',')
                    }
                    writer.appendLine()
                }
            writer.appendLine("  ]")
            writer.appendLine("}")
        }
    }

    private fun generateEventHandles(
        rootPackage: String,
        moduleName: String,
        dispatcherKey: String,
        bindings: List<EventHandlerBinding>,
    ) {
        val generatedPackage = "$rootPackage.generated"
        val contextType = sharedContextType(rootPackage, dispatcherKey, bindings) ?: return
        val objectName = "Generated${moduleName}${dispatcherKey.toDispatcherTypeNamePart()}EventHandles"
        val eventHandleType = EVENT_HANDLE.parameterizedBy(contextType)
        val chunks = bindings.sortedWith(compareBy({ it.order }, { it.handler.qualifiedName?.asString().orEmpty() }))
            .chunked(HANDLER_CHUNK_SIZE)
        FileSpec.builder(generatedPackage, objectName)
            .addType(
                TypeSpec.objectBuilder(objectName)
                    .addFunction(
                        FunSpec.builder("all")
                            .returns(List::class.asClassName().parameterizedBy(eventHandleType))
                            .addCode(buildHandlesAggregatorCode(objectName, generatedPackage, chunks.size))
                            .build(),
                    )
                    .build(),
            )
            .build()
            .writeTo(
                codeGenerator,
                Dependencies(aggregating = false, *bindings.map(EventHandlerBinding::sourceFile).toTypedArray()),
            )
        chunks.forEachIndexed { index, chunk ->
            FileSpec.builder(generatedPackage, "${objectName}Chunk$index")
                .addType(
                    TypeSpec.objectBuilder("${objectName}Chunk$index")
                        .addFunction(
                            FunSpec.builder("all")
                                .returns(List::class.asClassName().parameterizedBy(eventHandleType))
                                .addCode(buildHandlesChunkCode(chunk))
                                .build(),
                        )
                        .build(),
                )
                .build()
                .writeTo(
                    codeGenerator,
                    Dependencies(aggregating = false, *bindings.map(EventHandlerBinding::sourceFile).toTypedArray()),
                )
        }
    }

    private fun sharedContextType(
        rootPackage: String,
        dispatcherKey: String,
        bindings: List<EventHandlerBinding>,
    ): TypeName? {
        val contextType = bindings.first().contextTypeName
        if (bindings.any { it.contextTypeName != contextType }) {
            diagnostics.error(
                code = "ASTERIA-EVENT-013",
                message = "All event handlers in one generated dispatcher must share the same context type.",
                symbol = bindings.first().handler,
                reason = "One generated EventDispatcher is parameterized by a single context type.",
                fix = "Use the same first handle parameter type under rootPackage=$rootPackage dispatcher=$dispatcherKey, or split handlers into separate generated modules.",
            )
            return null
        }
        return contextType
    }

    private fun buildRegistryExpression(
        rootPackage: String,
        moduleName: String,
        dispatcherKey: String,
        contextType: TypeName,
    ): CodeBlock {
        val handlesObject = ClassName(
            "$rootPackage.generated",
            "Generated${moduleName}${dispatcherKey.toDispatcherTypeNamePart()}EventHandles",
        )
        return CodeBlock.of(
            "%T(%T.all())",
            DEFAULT_EVENT_HANDLE_REGISTRY.parameterizedBy(contextType),
            handlesObject,
        )
    }

    private fun buildHandlesAggregatorCode(
        objectName: String,
        generatedPackage: String,
        chunkCount: Int,
    ): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add("return buildList {\n")
        repeat(chunkCount) { index ->
            builder.add("  addAll(%T.all())\n", ClassName(generatedPackage, "${objectName}Chunk$index"))
        }
        builder.add("}\n")
        return builder.build()
    }

    private fun buildHandlesChunkCode(bindings: List<EventHandlerBinding>): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add("return listOf(\n")
        val handles = bindings.flatMap { binding ->
            if (binding.topics.isEmpty()) {
                listOf(buildEventTypeHandle(binding))
            } else {
                binding.topics.map { topic -> buildTopicHandle(binding, topic) }
            }
        }
        handles.forEachIndexed { index, handle ->
            builder.add("  %L", handle)
            if (index != handles.lastIndex) {
                builder.add(",\n")
            } else {
                builder.add("\n")
            }
        }
        builder.add(")\n")
        return builder.build()
    }

    private fun buildEventTypeHandle(binding: EventHandlerBinding): CodeBlock {
        return CodeBlock.of(
            "%T.forEventType(%T::class, order = %L, key = %M(%T::class)) { context, event, publisher -> %L.handle(context, event, publisher) }",
            EVENT_HANDLE,
            binding.eventClassName,
            binding.order,
            EVENT_HANDLE_KEY,
            binding.handler.toClassName(),
            eventHandlerConstructors.instantiateExpression(binding.handler),
        )
    }

    private fun buildTopicHandle(binding: EventHandlerBinding, topic: String): CodeBlock {
        return CodeBlock.of(
            "%T.forTopic(%M(%S), order = %L, key = %M(%T::class, %S)) { context, event, publisher -> %L.handle(context, event, publisher) }",
            EVENT_HANDLE,
            EVENT_TOPIC_PATH,
            topic,
            binding.order,
            EVENT_HANDLE_KEY,
            binding.handler.toClassName(),
            topic,
            eventHandlerConstructors.instantiateExpression(binding.handler),
        )
    }

    private fun KSClassDeclaration.findAnnotation(qualifiedName: String): KSAnnotation? {
        return annotations.firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
        }
    }

    private fun KSClassDeclaration.isSubtypeOf(qualifiedName: String): Boolean {
        if (this.qualifiedName?.asString() == qualifiedName) {
            return true
        }
        return getAllSuperTypes().any { type ->
            (type.declaration as? KSClassDeclaration)?.qualifiedName?.asString() == qualifiedName
        }
    }

    private fun KSType.targetClassDeclaration(): KSClassDeclaration? {
        return when (val targetDeclaration = declaration) {
            is KSClassDeclaration -> targetDeclaration
            is KSTypeAlias -> targetDeclaration.type.resolve().targetClassDeclaration()
            else -> null
        }
    }

    private fun KSAnnotation.stringArg(name: String): String {
        return arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""
    }

    private fun KSAnnotation.stringListArg(name: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        return (arguments.firstOrNull { it.name?.asString() == name }?.value as? List<String>).orEmpty()
    }

    private fun KSAnnotation.classListArg(name: String): List<KSClassDeclaration> {
        val value = arguments.firstOrNull { it.name?.asString() == name }?.value
        return when (value) {
            is List<*> -> value.mapNotNull { (it as? KSType)?.declaration as? KSClassDeclaration }
            is KSType -> listOfNotNull(value.declaration as? KSClassDeclaration)
            else -> emptyList()
        }
    }

    private fun KSAnnotation.intArg(name: String): Int {
        return arguments.firstOrNull { it.name?.asString() == name }?.value as? Int ?: 0
    }

    private fun String.isValidEventTopicPath(): Boolean {
        return split('.').all { segment ->
            segment.isValidEventTopicSegment()
        }
    }

    private fun String.isValidEventTopicSegment(): Boolean {
        return isNotBlank() && all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private fun String.toUpperCamel(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun String.toDispatcherTypeNamePart(): String {
        val tokens = split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }.ifEmpty { listOf("default") }
        return tokens.joinToString("") { token ->
            token.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    private fun String.toDispatcherPropertyName(): String {
        val sanitized = map { char ->
            when {
                char.isLetterOrDigit() || char == '_' -> char
                else -> '_'
            }
        }.joinToString("")
        val normalized = if (sanitized.firstOrNull()?.isDigit() == true) "_$sanitized" else sanitized
        return normalized.ifBlank { "default" }
    }

    private fun String.toRegistryPropertyName(): String {
        return "${toDispatcherPropertyName()}Registry"
    }

    private fun String.toSnapshotFileName(): String {
        return map { char ->
            when {
                char.isLetterOrDigit() -> char
                else -> '_'
            }
        }.joinToString("").ifBlank { "root" }
    }

    private fun String.toTopicSegment(): String {
        return splitCamelCase().joinToString("-") { it.lowercase(Locale.getDefault()) }
    }

    private fun String.toUpperSnake(): String {
        return splitCamelCase().joinToString("_") { token ->
            token.uppercase(Locale.getDefault())
        }.ifBlank { "TOPIC" }
    }

    private fun String.splitCamelCase(): List<String> {
        return replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
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

    companion object {
        private val EVENT_HANDLE = ClassName("io.github.realmlabs.asteria.event", "EventHandle")
        private val EVENT_HANDLE_KEY = MemberName("io.github.realmlabs.asteria.event", "eventHandleKey")
        private val EVENT_DISPATCHER = ClassName("io.github.realmlabs.asteria.event", "EventDispatcher")
        private val DEFAULT_EVENT_HANDLE_REGISTRY =
            ClassName("io.github.realmlabs.asteria.event", "DefaultEventHandleRegistry")
        private val EVENT_TOPIC_PATH = MemberName("io.github.realmlabs.asteria.event", "eventTopicPath")
        private const val HANDLER_CHUNK_SIZE = 200
    }
}
