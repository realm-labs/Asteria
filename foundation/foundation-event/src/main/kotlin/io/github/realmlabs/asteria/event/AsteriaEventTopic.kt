package io.github.realmlabs.asteria.event

/**
 * Marks the root of a nested event topic catalog for KSP code generation.
 *
 * When [value] is blank, the generator derives the segment from the annotated class name.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaEventTopicRoot(
    val value: String = "",
)

/**
 * Marks a nested event topic segment under an [AsteriaEventTopicRoot] or another topic class.
 *
 * Generated topic paths follow the nesting hierarchy and are used by event handlers that bind through topic refs.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaEventTopic(
    val value: String = "",
)
