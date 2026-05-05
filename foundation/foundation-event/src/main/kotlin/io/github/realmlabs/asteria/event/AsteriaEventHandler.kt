package io.github.realmlabs.asteria.event

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaEventHandler(
    val dispatcher: String = "default",
    val topics: Array<String> = [],
    val topicRefs: Array<KClass<*>> = [],
    val order: Int = 0,
)
