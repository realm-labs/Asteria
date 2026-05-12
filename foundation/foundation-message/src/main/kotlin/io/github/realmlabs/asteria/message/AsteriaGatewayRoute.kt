package io.github.realmlabs.asteria.message

/**
 * Declares a gateway route hint for message code generation.
 *
 * [route] is a logical route expression understood by the generator, not a transport address. Generated metadata uses
 * it to connect inbound gateway protocol messages to [RouteTarget] values.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AsteriaGatewayRoute(
    val route: String,
)
