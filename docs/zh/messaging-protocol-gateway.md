# 消息、协议和网关

这组模块把“业务消息”、“协议 id”和“客户端连接”拆成独立层。业务可以只用消息分发，也可以叠加 protobuf 协议生成和 Netty 网关。

## 业务消息和 handler

`foundation-message` 的 `Message` 只是框架路由标记，序列化和投递由具体 runtime 决定。

```kotlin
data class EnterWorldReq(
    override val id: Long,
    val sessionId: String,
) : ShardMessage<Long>

class EnterWorldHandler : MessageHandler<PlayerHandlerContext, EnterWorldReq> {
    override fun handle(context: PlayerHandlerContext, message: EnterWorldReq) {
        context.player.enterWorld(message.id)
    }
}
```

`MessageDispatcher` 是 exact-type dispatch：注册 `BaseMessage` 不会自动处理所有子类。handler 多时使用
`foundation-message-ksp` 和 Gradle plugin 生成注册代码，避免启动时反射扫描。

## Protobuf 协议生成

`protocol-protobuf` 提供 gateway protocol registry；`rpc-protobuf` 提供 RPC registry。`protobuf-codegen` 从 proto 描述生成
Kotlin registry 文件。

```kotlin
plugins {
    id("io.github.realm-labs.asteria.protobuf-protocol-codegen")
}

asteriaProtobufProtocol {
    gateway {
        packageName.set("com.example.game.protocol.generated")
        className.set("GeneratedGatewayProtocol")
    }

    rpc {
        packageName.set("com.example.game.rpc.generated")
        className.set("GeneratedRpcProtocol")
    }
}
```

生成器会在协议很多时拆成 `GeneratedGatewayProtocolChunkN` / `GeneratedRpcProtocolChunkN`，主文件只负责聚合，避免单文件膨胀。

Gateway protocol 会校验方向：server-only message 不能作为客户端输入解码，client-only message 不能作为服务端下发编码。message
id 和 message type 都必须唯一；inbound message 如果需要进入业务路由，必须配置 route。

## Pekko RPC

`rpc-protobuf-pekko` 用显式 serializer 发送注册过的 protobuf RPC 消息。跨节点消息必须在 registry 中有稳定
id；否则升级或混部时无法保证反序列化兼容。

实体 RPC 还需要 entity id registry 或 shard extractor 能从消息中拿到 entity id。

RPC id 空间和 gateway protocol id 空间是分开的。Pekko serializer 只处理注册过的 `GeneratedMessage`；sharding extractor
遇到没有 entity id resolver 的消息会直接失败。

## Gateway session

`gateway-core` 的 `GatewaySession` 只描述连接态，不内置登录、玩家绑定或分布式 session：

```kotlin
val PlayerIdKey = GatewaySessionAttributeKey<Long>("playerId")

session.set(PlayerIdKey, playerId)
val playerId = session.get(PlayerIdKey)

session.write(GatewayFrame(payload))
session.close(GatewayCloseReason.Application)
```

认证、顶号、玩家到 session 的反向索引、跨节点踢人，都应该由业务或 GM/cluster adapter 实现。

## Netty transport

`gateway-netty` 提供 binary WebSocket transport：

```kotlin
val transport = NettyWebSocketGatewayServerTransport(
    options = NettyGatewayServerOptions(
        host = "0.0.0.0",
        port = 9000,
        websocketPath = "/gateway",
        maxFrameLength = 1024 * 1024,
    ),
)

transport.start(handler)
```

`GatewayTransportHandler` 接收连接、读包和断开事件。默认 TCP/WebSocket pipeline 只处理完整二进制 frame。如果项目使用内置的
`BinaryGatewayPacket` 头和 protobuf envelope，需要在自定义 `NettyGatewayPipelineInstaller` 中组合 `PacketCodec`、
`NettyProtobufCodec` 和 gateway message handler。网关 transport 不应该直接知道业务 actor 结构；需要转发到 actor runtime
时使用 `gateway-pekko` 或业务自己的 adapter。

`PekkoGatewayForwarder` 可以把 route 结果转给 entity、singleton、service actor 或本地 handler。`RouteTarget.GatewayLocal`
必须提供本地 handler；`GatewayMessageDispatcher` 本身不解码、不重试，只做 resolve 和 forward。

## 广播

`broadcast-core` 有进程内 `LocalBroadcastBus`，`broadcast-pekko` 扩展到 Pekko 集群，`broadcast-protobuf` 提供 protobuf
广播消息模型。广播适合通知类消息，不适合替代有明确 ack 和重试语义的 RPC。

广播 topic 是不透明字符串，框架不做业务过滤。广播语义是 at-most-once；本地 subscriber 同步执行，慢 subscriber 会阻塞同一次
publish 中同 topic 后续投递。Pekko 广播 payload 必须能被 ActorSystem 序列化；protobuf 广播建议用
`ProtobufBroadcastPayload`，不要直接广播未配置 serializer 的 generated message。
