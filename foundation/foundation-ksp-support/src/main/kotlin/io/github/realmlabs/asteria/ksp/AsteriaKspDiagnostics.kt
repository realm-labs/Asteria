package io.github.realmlabs.asteria.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*

/**
 * Emits stable, user-facing KSP diagnostics.
 *
 * Processor modules should report invalid user source through this class instead of throwing raw exceptions. The
 * formatted output includes a processor name and a stable code so build logs remain searchable across Gradle, IDE, and
 * CI output.
 */
class AsteriaKspDiagnostics(
    private val logger: KSPLogger,
    private val processor: String,
) {
    fun error(
        code: String,
        message: String,
        symbol: KSNode? = null,
        reason: String? = null,
        fix: String? = null,
        example: String? = null,
    ) {
        logger.error(buildMessage(code, message, symbol, reason, fix, example), symbol)
    }

    fun warn(
        code: String,
        message: String,
        symbol: KSNode? = null,
        reason: String? = null,
        fix: String? = null,
        example: String? = null,
    ) {
        logger.warn(buildMessage(code, message, symbol, reason, fix, example), symbol)
    }

    private fun buildMessage(
        code: String,
        message: String,
        symbol: KSNode?,
        reason: String?,
        fix: String?,
        example: String?,
    ): String {
        return buildString {
            append("[Asteria KSP][$processor][$code] ")
            append(message)
            symbol?.diagnosticName()?.let { append("\nSymbol: ").append(it) }
            reason?.let { append("\nReason: ").append(it) }
            fix?.let { append("\nFix: ").append(it) }
            example?.let { append("\nExample:\n").append(it) }
        }
    }
}

fun KSNode.diagnosticName(): String? {
    return when (this) {
        is KSClassDeclaration -> qualifiedName?.asString() ?: simpleName.asString()
        is KSFunctionDeclaration -> qualifiedName?.asString() ?: simpleName.asString()
        is KSPropertyDeclaration -> qualifiedName?.asString() ?: simpleName.asString()
        is KSValueParameter -> name?.asString()
        else -> null
    }
}
