package io.github.realmlabs.asteria.message

/**
 * Declares a logical message route hint for KSP message code generation.
 *
 * This annotation has no runtime behavior by itself. `foundation-message-ksp` consumes it and writes generated metadata
 * that records the relationship between a message handler and its route intent.
 *
 * [route] is a logical route expression understood by downstream tooling, not a transport address.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaMessageRoute(
    val route: String,
)
