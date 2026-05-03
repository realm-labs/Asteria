package io.github.realmlabs.asteria.rpc

data class RpcProtocol(
    val entityIds: RpcEntityIdRegistry = CompositeRpcEntityIdRegistry(),
)
