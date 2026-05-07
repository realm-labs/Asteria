package io.github.realmlabs.asteria.message

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaGatewayRoute(
    val route: String,
)
