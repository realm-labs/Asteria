package io.github.realmlabs.asteria.event.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import java.util.Locale

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
    private var generated = false
    private val eventHandlerAnnotationName = "io.github.realmlabs.asteria.event.AsteriaEventHandler"
    private val eventTopicRootAnnotationName = "io.github.realmlabs.asteria.event.AsteriaEventTopicRoot"
    private val eventTopicAnnotationName = "io.github.realmlabs.asteria.event.AsteriaEventTopic"
    private val gameEventName = "io.github.realmlabs.asteria.event.GameEvent"
    private val eventPublisherName = "io.github.realmlabs.asteria.event.EventPublisher"

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) {
            return emptyList()
        }
        val topicBindings = collectTopicBindings(resolver)
        topicBindings.groupBy(EventTopicBinding::rootPackage).forEach { (rootPackage, rootTopics) ->
            generateTopicPaths(rootPackage, rootTopics)
        }
        val topicPathsByClass = topicBindings.associateBy(
            { requireNotNull(it.declaration.qualifiedName).asString() },
            EventTopicBinding::path,
        )
        val bindings = resolver.getSymbolsWithAnnotation(eventHandlerAnnotationName)
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { declaration ->
                declaration.toEventHandlerBinding(
                    declaration.containingFile ?: return@mapNotNull null,
                    topicPathsByClass,
                )
            }
            .toList()
        bindings.groupBy(EventHandlerBinding::rootPackage).forEach { (rootPackage, rootBindings) ->
            generateDispatchers(rootPackage, rootBindings)
        }
        generated = true
        return emptyList()
    }

    private fun collectTopicBindings(resolver: Resolver): List<EventTopicBinding> {
        val declarations = (resolver.getSymbolsWithAnnotation(eventTopicRootAnnotationName) +
            resolver.getSymbolsWithAnnotation(eventTopicAnnotationName))
            .filterIsInstance<KSClassDeclaration>()
            .associateBy { it.qualifiedName?.asString().orEmpty() }
        val cache = linkedMapOf<String, EventTopicBinding>()
        declarations.values.forEach { declaration ->
            declaration.toTopicBinding(declarations, cache)
        }
        return cache.values.toList()
    }

    private fun KSClassDeclaration.toTopicBinding(
        declarations: Map<String, KSClassDeclaration>,
        cache: MutableMap<String, EventTopicBinding>,
    ): EventTopicBinding? {
        val qualifiedName = qualifiedName?.asString() ?: return null
        cache[qualifiedName]?.let { return it }
        if (classKind != ClassKind.OBJECT) {
            logger.error("event topic declaration must be an object", this)
            return null
        }
        val rootAnnotation = findAnnotation(eventTopicRootAnnotationName)
        val topicAnnotation = findAnnotation(eventTopicAnnotationName)
        if (rootAnnotation != null && topicAnnotation != null) {
            logger.error("event topic declaration cannot use both root and child topic annotations", this)
            return null
        }
        val segment = (rootAnnotation ?: topicAnnotation)?.stringArg("value")
            ?.ifBlank { simpleName.asString().toTopicSegment() }
            ?: return null
        if (!segment.isValidEventTopicSegment()) {
            logger.error("event topic segment must be non-blank and contain only letters, digits, '-' or '_'", this)
            return null
        }
        val parentDeclaration = parentDeclaration as? KSClassDeclaration
        val parentBinding = if (rootAnnotation == null) {
            val parentName = parentDeclaration?.qualifiedName?.asString()
            val parent = parentName?.let(declarations::get)
            if (parent == null) {
                logger.error("event topic child must be nested under another event topic declaration", this)
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
            sourceFile = containingFile ?: return null,
        )
        cache[qualifiedName] = binding
        return binding
    }

    private fun KSClassDeclaration.toEventHandlerBinding(
        sourceFile: KSFile,
        topicPathsByClass: Map<String, String>,
    ): EventHandlerBinding? {
        if (classKind != ClassKind.CLASS || Modifier.ABSTRACT in modifiers) {
            return null
        }
        val handleFunction = getDeclaredFunctions().singleOrNull {
            it.simpleName.asString() == "handle" && it.parameters.size == 3
        } ?: run {
            logger.error("event handler must define one handle(context, event, publisher) function", this)
            return null
        }
        val contextType = handleFunction.parameters[0].type.toTypeName()
        val eventDeclaration = handleFunction.parameters[1].type.resolve().targetClassDeclaration()
            ?: run {
                logger.error("event handler event parameter must be a class type", this)
                return null
            }
        if (!eventDeclaration.isSubtypeOf(gameEventName)) {
            logger.error("event handler event parameter must implement GameEvent", this)
            return null
        }
        val publisherDeclaration = handleFunction.parameters[2].type.resolve().declaration as? KSClassDeclaration
        if (publisherDeclaration?.qualifiedName?.asString() != eventPublisherName) {
            logger.error("event handler publisher parameter must be EventPublisher<C>", this)
            return null
        }
        val annotation = findAnnotation(eventHandlerAnnotationName) ?: return null
        val topicRefPaths = annotation.classListArg("topicRefs").mapNotNull { topicRef ->
            val topicPath = topicPathsByClass[topicRef.qualifiedName?.asString()]
            if (topicPath == null) {
                logger.error(
                    "event handler topicRefs must reference @AsteriaEventTopicRoot or @AsteriaEventTopic objects",
                    this,
                )
            }
            topicPath
        }
        val topics = (annotation.stringListArg("topics") + topicRefPaths).distinct()
        if (topics.any { !it.isValidEventTopicPath() }) {
            logger.error("event handler topics must be non-blank dot-separated topic paths", this)
            return null
        }
        if (topics.isNotEmpty() && eventDeclaration.qualifiedName?.asString() != gameEventName) {
            logger.error("topic event handlers must accept GameEvent as the event parameter", this)
            return null
        }
        val packageName = packageName.asString()
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
                    val contextType = sharedContextType(rootPackage, dispatcherKey, dispatcherBindings) ?: return@forEach
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
            logger.error("all event handlers under $rootPackage dispatcher=$dispatcherKey must share the same context")
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
            "%T.forEventType(%T::class, order = %L) { context, event, publisher -> %L.handle(context, event, publisher) }",
            EVENT_HANDLE,
            binding.eventClassName,
            binding.order,
            instantiateExpression(binding.handler),
        )
    }

    private fun buildTopicHandle(binding: EventHandlerBinding, topic: String): CodeBlock {
        return CodeBlock.of(
            "%T.forTopic(%M(%S), order = %L) { context, event, publisher -> %L.handle(context, event, publisher) }",
            EVENT_HANDLE,
            EVENT_TOPIC_PATH,
            topic,
            binding.order,
            instantiateExpression(binding.handler),
        )
    }

    private fun instantiateExpression(type: KSClassDeclaration): CodeBlock {
        val constructor = type.primaryConstructor
            ?: type.getConstructors().singleOrNull()
            ?: type.getConstructors().firstOrNull { it.parameters.isEmpty() }
            ?: error("no constructible constructor found for ${type.qualifiedName?.asString()}")
        if (constructor.parameters.isEmpty()) {
            return CodeBlock.of("%T()", type.toClassName())
        }
        val builder = CodeBlock.builder()
        builder.add("%T(", type.toClassName())
        constructor.parameters.forEachIndexed { index, parameter ->
            val dependency = parameter.type.resolve().declaration as? KSClassDeclaration
                ?: error("unsupported dependency type for ${type.qualifiedName?.asString()}")
            builder.add("%L", instantiateExpression(dependency))
            if (index != constructor.parameters.lastIndex) {
                builder.add(", ")
            }
        }
        builder.add(")")
        return builder.build()
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

    companion object {
        private val EVENT_HANDLE = ClassName("io.github.realmlabs.asteria.event", "EventHandle")
        private val EVENT_DISPATCHER = ClassName("io.github.realmlabs.asteria.event", "EventDispatcher")
        private val DEFAULT_EVENT_HANDLE_REGISTRY =
            ClassName("io.github.realmlabs.asteria.event", "DefaultEventHandleRegistry")
        private val EVENT_TOPIC_PATH = MemberName("io.github.realmlabs.asteria.event", "eventTopicPath")
        private const val HANDLER_CHUNK_SIZE = 200
    }
}
