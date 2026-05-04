package io.github.realmlabs.asteria.message

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaMessageHandler(
    val dispatcher: String,
)
