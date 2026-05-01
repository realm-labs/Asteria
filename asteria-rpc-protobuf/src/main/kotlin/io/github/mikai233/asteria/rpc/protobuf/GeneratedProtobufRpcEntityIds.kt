package io.github.mikai233.asteria.rpc.protobuf

import io.github.mikai233.asteria.rpc.RpcEntityIdRegistry
import io.github.mikai233.asteria.rpc.RpcEntityIdRegistryProvider

/**
 * Base class for generated protobuf RPC entity id providers.
 *
 * The generator emits one implementation and registers it with
 * `META-INF/services/io.github.mikai233.asteria.rpc.RpcEntityIdRegistryProvider`.
 */
abstract class GeneratedProtobufRpcEntityIds : RpcEntityIdRegistryProvider {
    final override fun create(): RpcEntityIdRegistry = registry()

    protected abstract fun registry(): ProtobufRpcEntityIdRegistry
}
