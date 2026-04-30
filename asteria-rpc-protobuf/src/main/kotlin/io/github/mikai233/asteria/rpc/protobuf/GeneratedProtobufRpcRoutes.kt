package io.github.mikai233.asteria.rpc.protobuf

import io.github.mikai233.asteria.rpc.RpcRouteRegistry
import io.github.mikai233.asteria.rpc.RpcRouteRegistryProvider

/**
 * Base class for generated route providers.
 *
 * A future proto-option generator should emit one implementation and register it with
 * `META-INF/services/io.github.mikai233.asteria.rpc.RpcRouteRegistryProvider`.
 */
abstract class GeneratedProtobufRpcRoutes : RpcRouteRegistryProvider {
    final override fun create(): RpcRouteRegistry = registry()

    protected abstract fun registry(): ProtobufRpcRouteRegistry
}
