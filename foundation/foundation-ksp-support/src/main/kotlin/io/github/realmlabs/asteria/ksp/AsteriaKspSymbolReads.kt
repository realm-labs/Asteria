package io.github.realmlabs.asteria.ksp

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * Result of reading one annotated KSP symbol.
 *
 * Processors must not silently drop symbols returned by `Resolver.getSymbolsWithAnnotation`. A symbol is either
 * successfully converted into a model, deferred to another KSP round because required type information is not ready, or
 * reported as invalid with a diagnostic.
 */
sealed interface AsteriaKspSymbolRead<out T> {
    data class Success<T>(val value: T) : AsteriaKspSymbolRead<T>
    data class Deferred(val symbol: AsteriaKspDeferredSymbol) : AsteriaKspSymbolRead<Nothing>
    data object Invalid : AsteriaKspSymbolRead<Nothing>
}

/**
 * One deferred annotated symbol and the reason it cannot be safely processed in the current round.
 */
data class AsteriaKspDeferredSymbol(
    val symbol: KSAnnotated,
    val reason: String,
)

/**
 * Returns successful values from a batch of annotated symbol reads.
 */
fun <T> Iterable<AsteriaKspSymbolRead<T>>.successfulValues(): List<T> {
    return mapNotNull { (it as? AsteriaKspSymbolRead.Success)?.value }
}

/**
 * Returns deferred symbols from a batch of annotated symbol reads.
 */
fun Iterable<AsteriaKspSymbolRead<*>>.deferredSymbols(): List<AsteriaKspDeferredSymbol> {
    return mapNotNull { (it as? AsteriaKspSymbolRead.Deferred)?.symbol }
}

/**
 * Converts an annotated symbol to a class declaration or reports a clear target error.
 */
fun KSAnnotated.asAnnotatedClassOrInvalid(
    diagnostics: AsteriaKspDiagnostics,
    code: String,
    annotationName: String,
    expectedTarget: String = "class",
): AsteriaKspSymbolRead<KSClassDeclaration> {
    val declaration = this as? KSClassDeclaration
    if (declaration != null) {
        return AsteriaKspSymbolRead.Success(declaration)
    }
    diagnostics.error(
        code = code,
        message = "$annotationName must target a $expectedTarget.",
        symbol = this,
        reason = "KSP returned a non-class annotated symbol, which cannot be represented in generated Asteria code.",
        fix = "Move $annotationName to a supported $expectedTarget declaration.",
    )
    return AsteriaKspSymbolRead.Invalid
}

/**
 * Emits final diagnostics for symbols that remained deferred after KSP finished all rounds.
 */
fun AsteriaKspDiagnostics.reportUnprocessedDeferredSymbols(
    code: String,
    message: String,
    deferred: Iterable<AsteriaKspDeferredSymbol>,
    fix: String,
) {
    for (symbol in deferred) {
        error(
            code = code,
            message = message,
            symbol = symbol.symbol,
            reason = symbol.reason,
            fix = fix,
        )
    }
}
