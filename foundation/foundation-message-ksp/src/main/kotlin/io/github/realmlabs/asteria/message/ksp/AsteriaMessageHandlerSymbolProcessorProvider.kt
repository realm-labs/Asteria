package io.github.realmlabs.asteria.message.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import java.util.*

class AsteriaMessageHandlerSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AsteriaMessageHandlerSymbolProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}

private data class HandlerBinding(
    val contextTypeName: TypeName,
    val handler: KSClassDeclaration,
    val messageDeclaration: KSClassDeclaration,
    val messageClassName: ClassName,
    val generatedMessage: Boolean,
    val dispatcher: String,
    val sourceFile: KSFile,
)

private data class GatewayRouteBinding(
    val handler: KSClassDeclaration,
    val messageClassName: ClassName,
    val sourceFile: KSFile,
    val route: String,
)

private data class MessageCodegenTarget(
    val generatedPackage: String,
    val moduleId: String,
    val generatedTypeNamePart: String,
)

private class AsteriaMessageHandlerSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private var generated = false
    private val messageHandlerAnnotationName = "io.github.realmlabs.asteria.message.AsteriaMessageHandler"
    private val gatewayRouteAnnotationName = "io.github.realmlabs.asteria.message.AsteriaGatewayRoute"

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) {
            return emptyList()
        }

        val handlerDeclarations = resolver.getSymbolsWithAnnotation(messageHandlerAnnotationName)
            .filterIsInstance<KSClassDeclaration>().toList()
        val gatewayRouteDeclarations = resolver.getSymbolsWithAnnotation(gatewayRouteAnnotationName)
            .filterIsInstance<KSClassDeclaration>().toList()

        val target = if (handlerDeclarations.isNotEmpty() || gatewayRouteDeclarations.isNotEmpty()) {
            codegenTarget() ?: run {
                generated = true
                return emptyList()
            }
        } else {
            null
        }

        val handlerBindings = handlerDeclarations
            .mapNotNull { declaration ->
                declaration.toHandlerBinding(declaration.containingFile ?: return@mapNotNull null)
            }
        if (handlerBindings.isNotEmpty()) {
            checkNotNull(target)
            if (messageCatalogEnabled()) {
                generateCatalog(target, handlerBindings)
            }
            generateDispatchers(target, handlerBindings)
        }

        val gatewayRouteBindings = gatewayRouteDeclarations
            .mapNotNull { declaration ->
                declaration.toGatewayRouteBinding(declaration.containingFile ?: return@mapNotNull null)
            }
        if (gatewayRouteBindings.isNotEmpty()) {
            checkNotNull(target)
            generateGatewayRouteMetadata(target, gatewayRouteBindings)
        }
        target?.let { generateCodegenSnapshot(it, handlerBindings, gatewayRouteBindings) }

        generated = true
        return emptyList()
    }

    private fun KSClassDeclaration.toHandlerBinding(sourceFile: KSFile): HandlerBinding? {
        if (classKind != ClassKind.CLASS || Modifier.ABSTRACT in modifiers) {
            logger.error("@AsteriaMessageHandler must target a non-abstract class: ${qualifiedName?.asString()}", this)
            return null
        }
        val handleFunction = getDeclaredFunctions().firstOrNull {
            it.simpleName.asString() == "handle" && it.parameters.size == 2
        } ?: run {
            logger.error(
                "@AsteriaMessageHandler class must define handle(context, message): ${qualifiedName?.asString()}",
                this
            )
            return null
        }
        val contextType = handleFunction.parameters[0].type.toTypeName()
        val messageDeclaration = handleFunction.parameters[1].type.resolve().targetClassDeclaration()
            ?: run {
                logger.error(
                    "@AsteriaMessageHandler message parameter must be a class type: ${qualifiedName?.asString()}",
                    this
                )
                return null
            }
        val annotation = findAnnotation(messageHandlerAnnotationName) ?: return null
        return HandlerBinding(
            contextTypeName = contextType,
            handler = this,
            messageDeclaration = messageDeclaration,
            messageClassName = messageDeclaration.toClassName(),
            generatedMessage = messageDeclaration.isGeneratedMessage(),
            dispatcher = annotation.stringArg("dispatcher"),
            sourceFile = sourceFile,
        )
    }

    private fun KSClassDeclaration.toGatewayRouteBinding(sourceFile: KSFile): GatewayRouteBinding? {
        if (classKind != ClassKind.CLASS || Modifier.ABSTRACT in modifiers) {
            logger.error("@AsteriaGatewayRoute must target a non-abstract class: ${qualifiedName?.asString()}", this)
            return null
        }
        val handleFunction = getDeclaredFunctions().firstOrNull {
            it.simpleName.asString() == "handle" && it.parameters.size == 2
        } ?: run {
            logger.error(
                "@AsteriaGatewayRoute class must define handle(context, message): ${qualifiedName?.asString()}",
                this
            )
            return null
        }
        val messageDeclaration = handleFunction.parameters[1].type.resolve().targetClassDeclaration()
            ?: run {
                logger.error(
                    "@AsteriaGatewayRoute message parameter must be a class type: ${qualifiedName?.asString()}",
                    this
                )
                return null
            }
        val annotation = findAnnotation(gatewayRouteAnnotationName) ?: return null
        val route = annotation.stringArg("route")
        if (route.isBlank()) {
            logger.error("@AsteriaGatewayRoute route must not be blank: ${qualifiedName?.asString()}", this)
            return null
        }
        return GatewayRouteBinding(
            handler = this,
            messageClassName = messageDeclaration.toClassName(),
            sourceFile = sourceFile,
            route = route,
        )
    }

    private fun codegenTarget(): MessageCodegenTarget? {
        val generatedPackage = options[GENERATED_PACKAGE_OPTION]?.trim().orEmpty()
        val moduleId = options[MODULE_ID_OPTION]?.trim().orEmpty()
        var valid = true
        if (generatedPackage.isBlank()) {
            logger.error("KSP option $GENERATED_PACKAGE_OPTION must be configured when using Asteria message codegen")
            valid = false
        }
        if (moduleId.isBlank()) {
            logger.error("KSP option $MODULE_ID_OPTION must be configured when using Asteria message codegen")
            valid = false
        }
        return if (valid) {
            MessageCodegenTarget(
                generatedPackage = generatedPackage,
                moduleId = moduleId,
                generatedTypeNamePart = moduleId.toTypeNamePart(default = "Module"),
            )
        } else {
            null
        }
    }

    private fun messageCatalogEnabled(): Boolean {
        val value = options[MESSAGE_CATALOG_ENABLED_OPTION] ?: return false
        return when (value.lowercase(Locale.getDefault())) {
            "true" -> true
            "false" -> false
            else -> {
                logger.error("KSP option $MESSAGE_CATALOG_ENABLED_OPTION must be true or false")
                true
            }
        }
    }

    private fun generateCatalog(target: MessageCodegenTarget, bindings: List<HandlerBinding>) {
        val sourceFiles = bindings.map(HandlerBinding::sourceFile).toTypedArray()
        val catalogBindings = bindings.map { binding ->
            MessageCatalogBindingModel(
                messageClassName = binding.messageClassName,
                handlerClassName = binding.handler.toClassName(),
                dispatcher = binding.dispatcher,
            )
        }
        AsteriaMessageCatalogCodeGenerator.buildFiles(
            generatedPackage = target.generatedPackage,
            typeNamePart = target.generatedTypeNamePart,
            bindings = catalogBindings,
        ).forEach { generated ->
            generated.file.writeTo(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(
                    aggregating = false,
                    *sourceFiles,
                ),
            )
        }
    }

    private fun generateDispatchers(target: MessageCodegenTarget, bindings: List<HandlerBinding>) {
        val generatedPackage = target.generatedPackage
        val typeNamePart = target.generatedTypeNamePart
        val contextType = bindings.first().contextTypeName
        if (bindings.any { it.contextTypeName != contextType }) {
            logger.error("all @AsteriaMessageHandler bindings in module ${target.moduleId} must share the same handler context")
            return
        }
        val dispatcherType = ClassName("io.github.realmlabs.asteria.message", "MessageDispatcher")
        val generatedMessageType = ClassName("com.google.protobuf", "GeneratedMessage")
        val dispatchers = bindings.map { it.dispatcher }.distinct().sorted()
        dispatchers.forEach { dispatcherKey ->
            val dispatcherBindings = bindings.filter { it.dispatcher == dispatcherKey }
            val messageSuperType = resolveMessageSuperType(dispatcherKey, dispatcherBindings, generatedMessageType)
            generateDispatcherHandles(target, contextType, dispatcherKey, dispatcherBindings, messageSuperType)
        }
        val generatedType = TypeSpec.objectBuilder("Generated${typeNamePart}NodeDispatchers")
            .apply {
                dispatchers.forEach { dispatcherKey ->
                    val dispatcherBindings = bindings.filter { it.dispatcher == dispatcherKey }
                    val messageSuperType = resolveMessageSuperType(dispatcherKey, dispatcherBindings, generatedMessageType)
                    addProperty(
                        PropertySpec.builder(
                            dispatcherKey.toDispatcherPropertyName(),
                            dispatcherType.parameterizedBy(contextType, messageSuperType),
                        )
                            .initializer(buildDispatcherExpression(target, dispatcherBindings, messageSuperType))
                            .build(),
                    )
                }
            }
            .build()
        FileSpec.builder(generatedPackage, "Generated${typeNamePart}NodeDispatchers")
            .addType(generatedType)
            .build()
            .writeTo(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(
                    aggregating = false,
                    *bindings.map(HandlerBinding::sourceFile).toTypedArray(),
                ),
            )
    }

    private fun generateDispatcherHandles(
        target: MessageCodegenTarget,
        contextType: TypeName,
        dispatcherKey: String,
        bindings: List<HandlerBinding>,
        messageSuperType: ClassName,
    ) {
        val generatedPackage = target.generatedPackage
        val typeNamePart = target.generatedTypeNamePart
        val objectName = "Generated${typeNamePart}${dispatcherKey.toDispatcherTypeNamePart()}MessageHandles"
        val messageHandleType = ClassName("io.github.realmlabs.asteria.message", "MessageHandle")
            .parameterizedBy(contextType, messageSuperType)
        val chunkedBindings = bindings.sortedBy { it.messageClassName.canonicalName }.chunked(HANDLER_CHUNK_SIZE)
        val generatedType = TypeSpec.objectBuilder(objectName)
            .addFunction(
                FunSpec.builder("all")
                    .returns(List::class.asClassName().parameterizedBy(messageHandleType))
                    .addCode(buildHandlesAggregatorCode(objectName, generatedPackage, chunkedBindings.size))
                    .build(),
            )
            .build()
        FileSpec.builder(generatedPackage, objectName)
            .addType(generatedType)
            .build()
            .writeTo(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(
                    aggregating = false,
                    *bindings.map(HandlerBinding::sourceFile).toTypedArray(),
                ),
            )
        chunkedBindings.forEachIndexed { index, chunk ->
            val chunkObjectName = "${objectName}Chunk$index"
            FileSpec.builder(generatedPackage, chunkObjectName)
                .addType(
                    TypeSpec.objectBuilder(chunkObjectName)
                        .addFunction(
                            FunSpec.builder("all")
                                .returns(List::class.asClassName().parameterizedBy(messageHandleType))
                                .addCode(buildHandlesChunkCode(chunk))
                                .build(),
                        )
                        .build(),
                )
                .build()
                .writeTo(
                    codeGenerator = codeGenerator,
                    dependencies = Dependencies(
                        aggregating = false,
                        *bindings.map(HandlerBinding::sourceFile).toTypedArray(),
                    ),
                )
        }
    }

    private fun generateGatewayRouteMetadata(target: MessageCodegenTarget, bindings: List<GatewayRouteBinding>) {
        val moduleId = target.moduleId.lowercase(Locale.getDefault())
        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(
                aggregating = false,
                *bindings.map(GatewayRouteBinding::sourceFile).toTypedArray(),
            ),
            packageName = "META-INF/asteria/gateway-route-hints",
            fileName = moduleId,
            extensionName = "json",
        )
        output.bufferedWriter().use { writer ->
            writer.appendLine("{")
            writer.appendLine("  \"schemaVersion\": 1,")
            writer.appendLine("  \"module\": \"$moduleId\",")
            writer.appendLine("  \"routes\": [")
            bindings.sortedBy { it.messageClassName.canonicalName }.forEachIndexed { index, binding ->
                writer.appendLine("    {")
                writer.appendLine("      \"messageType\": ${jsonString(binding.messageClassName.canonicalName)},")
                writer.appendLine("      \"handlerType\": ${jsonString(binding.handler.toClassName().canonicalName)},")
                writer.appendLine("      \"route\": ${jsonString(binding.route)}")
                writer.append("    }")
                if (index != bindings.lastIndex) {
                    writer.append(',')
                }
                writer.appendLine()
            }
            writer.appendLine("  ]")
            writer.appendLine("}")
        }
    }

    private fun generateCodegenSnapshot(
        target: MessageCodegenTarget,
        handlerBindings: List<HandlerBinding>,
        gatewayRouteBindings: List<GatewayRouteBinding>,
    ) {
        val sourceFiles = (handlerBindings.map(HandlerBinding::sourceFile) +
            gatewayRouteBindings.map(GatewayRouteBinding::sourceFile))
            .distinctBy { it.filePath }
            .toTypedArray()
        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, *sourceFiles),
            packageName = "META-INF/asteria/codegen-snapshots/message",
            fileName = target.moduleId.lowercase(Locale.getDefault()),
            extensionName = "json",
        )
        output.bufferedWriter().use { writer ->
            writer.appendLine("{")
            writer.appendLine("  \"schemaVersion\": 1,")
            writer.appendLine("  \"kind\": \"message\",")
            writer.appendLine("  \"moduleId\": ${jsonString(target.moduleId)},")
            writer.appendLine("  \"generatedPackage\": ${jsonString(target.generatedPackage)},")
            writer.appendLine("  \"handlers\": [")
            handlerBindings
                .sortedWith(compareBy({ it.dispatcher }, { it.messageClassName.canonicalName }))
                .forEachIndexed { index, binding ->
                    writer.appendLine("    {")
                    writer.appendLine("      \"dispatcher\": ${jsonString(binding.dispatcher)},")
                    writer.appendLine("      \"contextType\": ${jsonString(binding.contextTypeName.toString())},")
                    writer.appendLine("      \"messageType\": ${jsonString(binding.messageClassName.canonicalName)},")
                    writer.appendLine("      \"handlerType\": ${jsonString(binding.handler.toClassName().canonicalName)},")
                    writer.appendLine("      \"generatedMessage\": ${binding.generatedMessage}")
                    writer.append("    }")
                    if (index != handlerBindings.lastIndex) {
                        writer.append(',')
                    }
                    writer.appendLine()
                }
            writer.appendLine("  ],")
            writer.appendLine("  \"gatewayRoutes\": [")
            gatewayRouteBindings
                .sortedBy { it.messageClassName.canonicalName }
                .forEachIndexed { index, binding ->
                    writer.appendLine("    {")
                    writer.appendLine("      \"messageType\": ${jsonString(binding.messageClassName.canonicalName)},")
                    writer.appendLine("      \"handlerType\": ${jsonString(binding.handler.toClassName().canonicalName)},")
                    writer.appendLine("      \"route\": ${jsonString(binding.route)}")
                    writer.append("    }")
                    if (index != gatewayRouteBindings.lastIndex) {
                        writer.append(',')
                    }
                    writer.appendLine()
                }
            writer.appendLine("  ]")
            writer.appendLine("}")
        }
    }

    private fun resolveMessageSuperType(
        dispatcherKey: String,
        bindings: List<HandlerBinding>,
        generatedMessageType: ClassName,
    ): ClassName {
        val configuredType = options["asteria.message.dispatcher.$dispatcherKey.superType"]
        if (configuredType != null) {
            val qualifiedName = configuredType.trim()
            val valid = bindings.all { it.messageDeclaration.isSubtypeOf(qualifiedName) }
            if (!valid) {
                logger.error(
                    "dispatcher $dispatcherKey configured superType=$qualifiedName but some messages are not subtypes of it",
                )
            }
            return ClassName.bestGuess(qualifiedName)
        }
        return if (bindings.all { it.generatedMessage }) {
            generatedMessageType
        } else {
            Any::class.asClassName()
        }
    }

    private fun buildDispatcherExpression(
        target: MessageCodegenTarget,
        bindings: List<HandlerBinding>,
        messageSuperType: ClassName,
    ): CodeBlock {
        val typeNamePart = target.generatedTypeNamePart
        val registryType = ClassName("io.github.realmlabs.asteria.message", "PatchableMessageHandlerRegistry")
        val dispatcherType = ClassName("io.github.realmlabs.asteria.message", "MessageDispatcher")
        val handlesObject = ClassName(
            target.generatedPackage,
            "Generated${typeNamePart}${bindings.first().dispatcher.toDispatcherTypeNamePart()}MessageHandles",
        )
        return CodeBlock.of(
            "%T(%T(%T.all()))",
            dispatcherType,
            registryType.parameterizedBy(bindings.first().contextTypeName, messageSuperType),
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

    private fun buildHandlesChunkCode(bindings: List<HandlerBinding>): CodeBlock {
        val messageHandleType = ClassName("io.github.realmlabs.asteria.message", "MessageHandle")
        val builder = CodeBlock.builder()
        builder.add("return listOf(\n")
        bindings.forEachIndexed { index, binding ->
            builder.add(
                "  %T.of(%T::class, %L)",
                messageHandleType,
                binding.messageClassName,
                instantiateExpression(binding.handler),
            )
            if (index != bindings.lastIndex) {
                builder.add(",\n")
            } else {
                builder.add("\n")
            }
        }
        builder.add(")\n")
        return builder.build()
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

    private fun KSClassDeclaration.isGeneratedMessage(): Boolean {
        if (qualifiedName?.asString() == "com.google.protobuf.GeneratedMessage") {
            return true
        }
        return getAllSuperTypes().any { type ->
            (type.declaration as? KSClassDeclaration)?.qualifiedName?.asString() == "com.google.protobuf.GeneratedMessage"
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

    private fun String.toDispatcherTypeNamePart(): String {
        return toTypeNamePart(default = "Default")
    }

    private fun String.toTypeNamePart(default: String): String {
        val tokens = split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }.ifEmpty { listOf(default) }
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
        return normalized.ifBlank { "DEFAULT" }
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
        private const val HANDLER_CHUNK_SIZE = 200
        private const val GENERATED_PACKAGE_OPTION = "asteria.message.generated.package"
        private const val MODULE_ID_OPTION = "asteria.message.module.id"
        private const val MESSAGE_CATALOG_ENABLED_OPTION = "asteria.message.catalog.enabled"
    }
}
