package io.github.realmlabs.asteria.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Shared constructor rules for generated KSP factories.
 *
 * Asteria processors frequently generate static handler lists. This helper keeps the "what can codegen instantiate"
 * rule consistent: objects are referenced directly; classes need a selected constructor whose parameters are themselves
 * constructible classes or objects.
 */
class AsteriaKspConstructors(
    private val diagnostics: AsteriaKspDiagnostics,
    private val codePrefix: String,
    private val annotationName: String,
) {
    fun validateConstructible(type: KSClassDeclaration): Boolean {
        return validateConstructible(type, emptyList())
    }

    fun instantiateExpression(type: KSClassDeclaration): CodeBlock {
        if (type.classKind == ClassKind.OBJECT) {
            return CodeBlock.of("%T", type.toClassName())
        }
        val constructor = selectConstructibleConstructor(type)
            ?: error("constructible constructor was validated before generation for ${type.qualifiedName?.asString()}")
        if (constructor.parameters.isEmpty()) {
            return CodeBlock.of("%T()", type.toClassName())
        }
        val builder = CodeBlock.builder()
        builder.add("%T(", type.toClassName())
        constructor.parameters.forEachIndexed { index, parameter ->
            val dependency = parameter.type.resolve().declaration as? KSClassDeclaration
                ?: error("constructible constructor parameter was validated before generation for ${type.qualifiedName?.asString()}")
            builder.add("%L", instantiateExpression(dependency))
            if (index != constructor.parameters.lastIndex) {
                builder.add(", ")
            }
        }
        builder.add(")")
        return builder.build()
    }

    private fun validateConstructible(
        type: KSClassDeclaration,
        path: List<String>,
    ): Boolean {
        val qualifiedName = type.qualifiedName?.asString() ?: type.simpleName.asString()
        if (qualifiedName in path) {
            diagnostics.error(
                code = "$codePrefix-CONSTRUCTOR-RECURSIVE",
                message = "$annotationName constructor dependency graph is recursive.",
                symbol = type,
                reason = "The generated factory expands constructor calls at compile time and cannot build recursive dependencies.",
                fix = "Use a zero-argument constructor, an object dependency, or break the constructor cycle.",
            )
            return false
        }
        if (type.classKind == ClassKind.OBJECT) {
            return true
        }
        val constructor = selectConstructibleConstructor(type)
        if (constructor == null) {
            diagnostics.error(
                code = "$codePrefix-CONSTRUCTOR-MISSING",
                message = "$annotationName class has no constructible constructor.",
                symbol = type,
                reason = "Codegen can instantiate a zero-argument constructor, or a constructor whose parameters are themselves constructible classes or objects.",
                fix = "Add a public zero-argument constructor, convert dependencies to objects, or make constructor parameters constructible.",
            )
            return false
        }
        return constructor.parameters.all { parameter ->
            val dependency = parameter.type.resolve().declaration as? KSClassDeclaration
            if (dependency == null) {
                diagnostics.error(
                    code = "$codePrefix-CONSTRUCTOR-PARAMETER",
                    message = "$annotationName constructor parameter type is unsupported.",
                    symbol = parameter,
                    reason = "Constructor parameters must resolve to concrete classes or objects that codegen can instantiate.",
                    fix = "Use a concrete class/object dependency, or provide a zero-argument constructor.",
                )
                false
            } else {
                validateConstructible(dependency, path + qualifiedName)
            }
        }
    }

    private fun selectConstructibleConstructor(type: KSClassDeclaration): KSFunctionDeclaration? {
        return type.primaryConstructor
            ?: type.getConstructors().singleOrNull()
            ?: type.getConstructors().firstOrNull { it.parameters.isEmpty() }
    }
}
