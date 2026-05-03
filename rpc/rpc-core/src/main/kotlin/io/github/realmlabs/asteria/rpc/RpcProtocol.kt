package io.github.realmlabs.asteria.rpc

data class RpcProtocol(
    val methods: RpcMethodRegistry = StaticRpcMethodRegistry(),
    val entityIds: RpcEntityIdRegistry = CompositeRpcEntityIdRegistry(),
)
