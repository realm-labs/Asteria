package io.github.realmlabs.asteria.message

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaGatewayRoute(
    val route: String,
    val entityId: String = "",
    val inject: Array<String> = [],
    val clearFields: Array<String> = [],
)
