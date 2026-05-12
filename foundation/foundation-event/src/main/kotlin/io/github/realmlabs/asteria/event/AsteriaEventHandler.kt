package io.github.realmlabs.asteria.event

import kotlin.reflect.KClass

/**
 * Marks a class as an event handler source for KSP-generated dispatcher wiring.
 *
 * [dispatcher] selects the generated registry group. [topics] and [topicRefs] add topic-based subscriptions in
 * addition to the event type handled by the class. [order] controls invocation order within one dispatch route.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaEventHandler(
    val dispatcher: String = "default",
    val topics: Array<String> = [],
    val topicRefs: Array<KClass<*>> = [],
    val order: Int = 0,
)
