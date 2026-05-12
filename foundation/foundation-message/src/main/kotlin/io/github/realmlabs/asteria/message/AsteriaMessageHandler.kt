package io.github.realmlabs.asteria.message

/**
 * Marks a class as a source message handler for KSP-generated dispatcher wiring.
 *
 * The annotated class is expected to expose the handler shape consumed by the message code generator. [dispatcher]
 * selects the logical dispatcher key, which is later used to group generated handles and optional super types.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaMessageHandler(
    val dispatcher: String,
)
