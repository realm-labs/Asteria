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
import io.github.realmlabs.asteria.message.MessageCatalog
import io.github.realmlabs.asteria.message.MessageCatalogEntry
import java.util.*

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

private class AsteriaMessageHandlerSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private var generated = false
    private val annotationName = "io.github.realmlabs.asteria.message.AsteriaMessageHandler"

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) {
            return emptyList()
        }
        val symbols = resolver.getSymbolsWithAnnotation(annotationName).toList()
        val bindings = symbols
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { declaration ->
                declaration.toBinding(declaration.containingFile ?: return@mapNotNull null)
            }
        if (bindings.isEmpty()) {
            generated = true
            return emptyList()
        }
        bindings.groupBy(HandlerBinding::rootPackage).forEach { (rootPackage, rootBindings) ->
            generateCatalog(rootPackage, rootBindings)
            generateDispatchers(rootPackage, rootBindings)
        }
        generated = true
        return emptyList()
    }

    private fun KSClassDeclaration.toBinding(sourceFile: KSFile): HandlerBinding? {
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
        val annotation = findAnnotation(annotationName) ?: return null
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
                    *bindings.map(HandlerBinding::sourceFile).toTypedArray()
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
        val generatedType = TypeSpec.objectBuilder("Generated${moduleName}NodeDispatchers")
            .apply {
                bindings.map { it.dispatcher }.distinct().sorted().forEach { dispatcherKey ->
                    val dispatcherBindings = bindings.filter { it.dispatcher == dispatcherKey }
                    val messageSuperType =
                        resolveMessageSuperType(dispatcherKey, dispatcherBindings, generatedMessageType)
                    addProperty(
                        PropertySpec.builder(
                            dispatcherKey.toDispatcherPropertyName(),
                            dispatcherType.parameterizedBy(contextType, messageSuperType),
                        )
                            .initializer(buildDispatcherExpression(contextType, dispatcherBindings, messageSuperType))
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
                    *bindings.map(HandlerBinding::sourceFile).toTypedArray()
                ),
            )
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
        contextType: TypeName,
        bindings: List<HandlerBinding>,
        messageSuperType: ClassName,
    ): CodeBlock {
        val registryType = ClassName("io.github.realmlabs.asteria.message", "PatchableMessageHandlerRegistry")
        val dispatcherType = ClassName("io.github.realmlabs.asteria.message", "MessageDispatcher")
        val builder = CodeBlock.builder()
        builder.add("%T(%T<%T, %T>().apply {\n", dispatcherType, registryType, contextType, messageSuperType)
        bindings.sortedBy { it.messageClassName.canonicalName }.forEach { binding ->
            builder.add(
                "  register(%T::class, %L::handle)\n",
                binding.messageClassName,
                instantiateExpression(binding.handler)
            )
        }
        builder.add("})")
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
        val qualifiedName = qualifiedName?.asString()
        if (qualifiedName == "com.google.protobuf.GeneratedMessage") {
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
        return when (val declaration = declaration) {
            is KSClassDeclaration -> declaration
            is KSTypeAlias -> declaration.type.resolve().targetClassDeclaration()
            else -> null
        }
    }

    private fun KSAnnotation.stringArg(name: String): String {
        return arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""
    }

    private fun String.toUpperCamel(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
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
}
