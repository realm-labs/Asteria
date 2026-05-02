package io.github.mikai233.asteria.rpc

data class RpcProtocol(
    val methods: RpcMethodRegistry = StaticRpcMethodRegistry(),
    val entityIds: RpcEntityIdRegistry = CompositeRpcEntityIdRegistry(),
)
