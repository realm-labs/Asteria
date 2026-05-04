package io.github.realmlabs.asteria.message.ksp

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
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.realmlabs.asteria.message.MessageCatalog
import io.github.realmlabs.asteria.message.MessageCatalogEntry
import java.util.Locale

class AsteriaMessageHandlerSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AsteriaMessageHandlerSymbolProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}

private data class HandlerBinding(
    val rootPackage: String,
    val contextTypeName: TypeName,
    val handler: KSClassDeclaration,
    val messageDeclaration: KSClassDeclaration,
    val messageClassName: ClassName,
    val generatedMessage: Boolean,
    val dispatcher: String,
    val sourceFile: KSFile,
)

private data class GatewayRouteBinding(
    val rootPackage: String,
    val handler: KSClassDeclaration,
    val messageClassName: ClassName,
    val sourceFile: KSFile,
    val route: String,
    val entityId: String,
    val inject: List<String>,
    val clearFields: List<String>,
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

        val handlerBindings = resolver.getSymbolsWithAnnotation(messageHandlerAnnotationName)
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { declaration ->
                declaration.toHandlerBinding(declaration.containingFile ?: return@mapNotNull null)
            }
            .toList()
        if (handlerBindings.isNotEmpty()) {
            handlerBindings.groupBy(HandlerBinding::rootPackage).forEach { (rootPackage, bindings) ->
                generateCatalog(rootPackage, bindings)
                generateDispatchers(rootPackage, bindings)
            }
        }

        val gatewayRouteBindings = resolver.getSymbolsWithAnnotation(gatewayRouteAnnotationName)
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { declaration ->
                declaration.toGatewayRouteBinding(declaration.containingFile ?: return@mapNotNull null)
            }
            .toList()
        if (gatewayRouteBindings.isNotEmpty()) {
            gatewayRouteBindings.groupBy(GatewayRouteBinding::rootPackage).forEach { (rootPackage, bindings) ->
                generateGatewayRouteMetadata(rootPackage, bindings)
            }
        }

        generated = true
        return emptyList()
    }

    private fun KSClassDeclaration.toHandlerBinding(sourceFile: KSFile): HandlerBinding? {
        if (classKind != ClassKind.CLASS || Modifier.ABSTRACT in modifiers) {
            return null
        }
        val packageName = packageName.asString()
        if (!packageName.contains(".handler.") || !simpleName.asString().endsWith("Handler")) {
            return null
        }
        val rootPackage = packageName.substringBefore(".handler.")
        val handleFunction = getDeclaredFunctions().firstOrNull {
            it.simpleName.asString() == "handle" && it.parameters.size == 2
        } ?: return null
        val contextType = handleFunction.parameters[0].type.toTypeName()
        val messageDeclaration = handleFunction.parameters[1].type.resolve().targetClassDeclaration()
            ?: return null
        val annotation = findAnnotation(messageHandlerAnnotationName) ?: return null
        return HandlerBinding(
            rootPackage = rootPackage,
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
            return null
        }
        val packageName = packageName.asString()
        if (!packageName.contains(".handler.") || !simpleName.asString().endsWith("Handler")) {
            return null
        }
        val rootPackage = packageName.substringBefore(".handler.")
        val handleFunction = getDeclaredFunctions().firstOrNull {
            it.simpleName.asString() == "handle" && it.parameters.size == 2
        } ?: run {
            logger.error("gateway route handler must define handle(context, message): ${qualifiedName?.asString()}", this)
            return null
        }
        val messageDeclaration = handleFunction.parameters[1].type.resolve().targetClassDeclaration()
            ?: run {
                logger.error("gateway route handler message must be a class type: ${qualifiedName?.asString()}", this)
                return null
            }
        val annotation = findAnnotation(gatewayRouteAnnotationName) ?: return null
        return GatewayRouteBinding(
            rootPackage = rootPackage,
            handler = this,
            messageClassName = messageDeclaration.toClassName(),
            sourceFile = sourceFile,
            route = annotation.stringArg("route"),
            entityId = annotation.stringArg("entityId"),
            inject = annotation.stringListArg("inject"),
            clearFields = annotation.stringListArg("clearFields"),
        )
    }

    private fun generateCatalog(rootPackage: String, bindings: List<HandlerBinding>) {
        val generatedPackage = "$rootPackage.generated"
        val moduleName = rootPackage.substringAfterLast('.').toUpperCamel()
        val generatedType = TypeSpec.objectBuilder("Generated${moduleName}MessageCatalog")
            .addSuperinterface(MessageCatalog::class)
            .addProperty(
                PropertySpec.builder(
                    "bindings",
                    List::class.asClassName().parameterizedBy(MessageCatalogEntry::class.asClassName()),
                )
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer(buildCatalogInitializer(bindings))
                    .build(),
            )
            .build()
        FileSpec.builder(generatedPackage, "Generated${moduleName}MessageCatalog")
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

    private fun generateDispatchers(rootPackage: String, bindings: List<HandlerBinding>) {
        val generatedPackage = "$rootPackage.generated"
        val moduleName = rootPackage.substringAfterLast('.').toUpperCamel()
        val contextType = bindings.first().contextTypeName
        if (bindings.any { it.contextTypeName != contextType }) {
            logger.error("all @AsteriaMessageHandler bindings under $rootPackage must share the same handler context")
            return
        }
        val dispatcherType = ClassName("io.github.realmlabs.asteria.message", "MessageDispatcher")
        val generatedMessageType = ClassName("com.google.protobuf", "GeneratedMessage")
        val dispatchers = bindings.map { it.dispatcher }.distinct().sorted()
        dispatchers.forEach { dispatcherKey ->
            val dispatcherBindings = bindings.filter { it.dispatcher == dispatcherKey }
            val messageSuperType = resolveMessageSuperType(dispatcherKey, dispatcherBindings, generatedMessageType)
            generateDispatcherHandles(rootPackage, moduleName, contextType, dispatcherKey, dispatcherBindings, messageSuperType)
        }
        val generatedType = TypeSpec.objectBuilder("Generated${moduleName}NodeDispatchers")
            .apply {
                dispatchers.forEach { dispatcherKey ->
                    val dispatcherBindings = bindings.filter { it.dispatcher == dispatcherKey }
                    val messageSuperType = resolveMessageSuperType(dispatcherKey, dispatcherBindings, generatedMessageType)
                    addProperty(
                        PropertySpec.builder(
                            dispatcherKey.toDispatcherPropertyName(),
                            dispatcherType.parameterizedBy(contextType, messageSuperType),
                        )
                            .initializer(buildDispatcherExpression(moduleName, dispatcherBindings, messageSuperType))
                            .build(),
                    )
                }
            }
            .build()
        FileSpec.builder(generatedPackage, "Generated${moduleName}NodeDispatchers")
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
        rootPackage: String,
        moduleName: String,
        contextType: TypeName,
        dispatcherKey: String,
        bindings: List<HandlerBinding>,
        messageSuperType: ClassName,
    ) {
        val generatedPackage = "$rootPackage.generated"
        val objectName = "Generated${moduleName}${dispatcherKey.toDispatcherTypeNamePart()}MessageHandles"
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

    private fun generateGatewayRouteMetadata(rootPackage: String, bindings: List<GatewayRouteBinding>) {
        val moduleName = rootPackage.substringAfterLast('.').lowercase(Locale.getDefault())
        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(
                aggregating = false,
                *bindings.map(GatewayRouteBinding::sourceFile).toTypedArray(),
            ),
            packageName = "META-INF/asteria/gateway-route-hints",
            fileName = moduleName,
            extensionName = "json",
        )
        output.bufferedWriter().use { writer ->
            writer.appendLine("{")
            writer.appendLine("  \"module\": \"$moduleName\",")
            writer.appendLine("  \"routes\": [")
            bindings.sortedBy { it.messageClassName.canonicalName }.forEachIndexed { index, binding ->
                writer.appendLine("    {")
                writer.appendLine("      \"messageType\": \"${binding.messageClassName.canonicalName}\",")
                writer.appendLine("      \"handlerType\": \"${binding.handler.toClassName().canonicalName}\",")
                writer.appendLine("      \"route\": \"${binding.route}\",")
                if (binding.entityId.isBlank()) {
                    writer.appendLine("      \"entityId\": null,")
                } else {
                    writer.appendLine("      \"entityId\": \"${binding.entityId}\",")
                }
                writer.appendLine("      \"inject\": ${stringArrayJson(binding.inject)},")
                writer.appendLine("      \"clearFields\": ${stringArrayJson(binding.clearFields)}")
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

    private fun buildCatalogInitializer(bindings: List<HandlerBinding>): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add("listOf(\n")
        bindings.sortedWith(compareBy({ it.dispatcher }, { it.messageClassName.canonicalName }))
            .forEachIndexed { index, binding ->
                builder.add(
                    "  %T(\n    messageClass = %T::class,\n    handlerClass = %T::class,\n    dispatcher = %S,\n  )",
                    MessageCatalogEntry::class,
                    binding.messageClassName,
                    binding.handler.toClassName(),
                    binding.dispatcher,
                )
                if (index != bindings.lastIndex) {
                    builder.add(",\n")
                } else {
                    builder.add("\n")
                }
            }
        builder.add(")")
        return builder.build()
    }

    private fun buildDispatcherExpression(
        moduleName: String,
        bindings: List<HandlerBinding>,
        messageSuperType: ClassName,
    ): CodeBlock {
        val registryType = ClassName("io.github.realmlabs.asteria.message", "PatchableMessageHandlerRegistry")
        val dispatcherType = ClassName("io.github.realmlabs.asteria.message", "MessageDispatcher")
        val handlesObject = ClassName(
            "${bindings.first().rootPackage}.generated",
            "Generated${moduleName}${bindings.first().dispatcher.toDispatcherTypeNamePart()}MessageHandles",
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

    private fun KSAnnotation.stringListArg(name: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        return (arguments.firstOrNull { it.name?.asString() == name }?.value as? List<String>).orEmpty()
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
        return normalized.ifBlank { "DEFAULT" }
    }

    private fun stringArrayJson(values: List<String>): String {
        return values.joinToString(prefix = "[", postfix = "]") { value -> "\"$value\"" }
    }

    companion object {
        private const val HANDLER_CHUNK_SIZE = 200
    }
}
