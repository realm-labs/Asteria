package io.github.realmlabs.asteria.event

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaEventTopicRoot(
    val value: String = "",
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaEventTopic(
    val value: String = "",
)
