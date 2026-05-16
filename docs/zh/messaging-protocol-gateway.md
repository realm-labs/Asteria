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

`MessageDispatcher` 是 exact-type dispatch：默认用消息运行时类型查找 handler，注册 `BaseMessage`
不会自动处理所有子类；只有显式调用带 `messageType` 的 overload 时，才会按传入类型查找。缺少 handler
或 handler 抛错都会向调用方暴露。

例如只注册了 `GameCommand` 的 handler 时，直接分发 `MoveCommand` 会查 `MoveCommand::class`，因此找不到 handler。
如果业务确实想让一组子类共用父接口 handler，需要调用显式类型版本：

```kotlin
sealed interface GameCommand

data class MoveCommand(val x: Int, val y: Int) : GameCommand

registry.register<GameCommand> { context, command ->
    context.commandBus.handle(command)
}

// 查找 key 是 MoveCommand::class；上面只注册了 GameCommand，所以会失败。
dispatcher.dispatch(context, MoveCommand(1, 2))

// 查找 key 是 GameCommand::class；会命中上面的父接口 handler。
dispatcher.dispatch(context, GameCommand::class, MoveCommand(1, 2))
```

handler 多时使用 `foundation-message-ksp` 和 Gradle plugin 生成注册代码，避免启动时反射扫描。

```kotlin
plugins {
    id("io.github.realm-labs.asteria.message-codegen")
}

asteriaMessageCodegen {
    generatedPackage.set("com.example.game.generated")
    moduleId.set("game")
}
```

`@AsteriaMessageHandler(dispatcher = "...")` 标记一个可生成注册代码的 handler class。handler 必须是非抽象 class，
并定义 `handle(context, message)`；第一个参数决定 dispatcher 的 `HandlerContext` 类型，第二个参数决定要注册的消息类型。
同一个生成模块内的 handler 必须共享同一种 context 类型。`dispatcher` 是逻辑分发器 key，用来把登录服、游戏服、内部消息等不同入口拆成不同
registry。

KSP 会按 `generatedPackage` 和 `moduleId` 生成：

- `Generated<Module>NodeDispatchers`：暴露每个 dispatcher key 对应的 `*_HANDLES`、`*_REGISTRY` 和 dispatcher。
- `Generated<Module><Dispatcher>MessageHandles` 及 chunk：保存静态 `MessageHandle` 列表。
- 可选 `MessageCatalog`：开启 `messageCatalogEnabled` 后供工具和诊断读取，不作为运行时路由入口。

生成的 registry 是 `PatchableMessageHandlerRegistry`。`MessageDispatcher` 每次分发都会从 registry 读取当前 slot：
基础 slot 来自 KSP 生成的 handles，补丁通过 `context.messageHandlers.replace(registry, ...)` 覆盖某一个消息类型的
slot。补丁不会重建
dispatcher，
也不会新增未注册过的消息类型；卸载补丁后会回退到下一层补丁或基础 handler。需要自定义 `MessageHandleRegistry`
时，可以复用生成的 handles 后自行构造 `MessageDispatcher`。

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

Gateway protocol registry 只负责协议 id、protobuf 类型、parser 和方向。它会校验方向：server-only message
不能作为客户端输入解码，client-only message 不能作为服务端下发编码。message id 和 message type 都必须唯一。

进入网关的 protobuf 消息还需要 route registry：`clientMessage` 和 `bidirectionalMessage` 会同时登记协议映射和
`RouteTarget`，`serverMessage` 只登记服务端下发协议。`ProtobufGatewayRouteResolver` 根据解码后的 envelope 查 route，
并可通过 id resolver 生成 `GatewayRoute.entityId`；它不执行鉴权、限流或业务 handler。

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

`GatewayTransportHandler` 接收连接、读包和断开事件。默认 TCP/WebSocket pipeline 只处理完整二进制 frame；transport
负责连接生命周期、frame 边界和 backpressure 相关的网络行为，不负责协议 id 映射、业务路由、鉴权或 actor 投递。

如果项目使用内置的 `BinaryGatewayPacket` 头和 protobuf envelope，需要在自定义 `NettyGatewayPipelineInstaller`
中组合 `PacketCodec`、`NettyProtobufCodec` 和 gateway message handler。`NettyProtobufCodec` 只把 frame 与 protobuf
envelope 互转，实际 route 由 protocol/gateway dispatcher 处理。

`GatewayMessageDispatcher` 连接 route resolver 和 forwarder：它接收已经解码好的 packet，解析出 `GatewayRoute`，
调用 forwarder，并返回 route 供日志或指标使用。它不解码、不重试、不管理 session 生命周期。

`PekkoGatewayForwarder` 是 `gateway-pekko` 的 actor runtime adapter，可以把 route 结果转给 entity、singleton、
service actor 或本地 handler。`RouteTarget.GatewayLocal` 必须提供本地 handler；`PekkoGatewayMessageFactory`
可把原始 packet 包装成 actor 需要的消息类型。业务如果不用 Pekko，可以实现自己的 `GatewayForwarder`，保持 transport
和 actor 拓扑解耦。

## 临时广播

`ephemeral-broadcast-core` 有进程内 `LocalEphemeralBroadcastBus`，`ephemeral-broadcast-pekko` 扩展到 Pekko
集群，`ephemeral-broadcast-protobuf` 提供 protobuf 广播消息模型。临时广播适合在线通知、缓存失效和 reload
signal，不适合承载持久业务事实。

临时广播 topic 是不透明字符串，框架不做业务过滤。投递语义是 at-most-once，不提供持久化、回放或离线补偿。
本地 subscriber 同步执行，慢 subscriber 会阻塞同一次 publish 中同 topic 后续投递。Pekko 临时广播 payload
必须能被 ActorSystem 序列化；protobuf 广播建议用 `ProtobufEphemeralBroadcastPayload`，不要直接广播未配置
serializer 的 generated message。

支付、发奖、邮件、审计日志、跨系统集成等需要持久化或回放的业务事件应使用 event stream，而不是临时广播。
`event-stream-core` 定义 broker-neutral envelope、publisher、consumer 和 delivery 契约；具体 backend 模块负责
持久化、投递确认、重试、死信和回放语义。内置 in-memory 实现只用于本地开发和测试。

## Event Stream

`DurableEventEnvelope` 表示已经发生且需要持久化的业务事实。`payload` 是原始字节，`type` 是稳定事件类型，
`key` 用于 backend 分区或有序投递，`eventId`、`correlationId` 和 `causationId` 用于幂等、链路关联和因果追踪。

`DurableEventPublisher.publish` 在 backend 接受事件后返回 `DurableEventPublishResult`。返回值可以携带 backend
分区、offset 和发布时间；它只表示事件已被接受发布，不表示任何 consumer 已完成处理。

`DurableEventConsumer.subscribe` 通过 `DurableEventSubscribeOptions` 声明消费组、起始位置和失败策略。起始位置可以是
latest、earliest、指定 timestamp 或指定 offset。默认投递契约是 at-least-once；handler 成功返回后 backend 才应确认
或提交进度。handler 抛错时，backend 根据 `DurableEventFailurePolicy` 和自身配置执行重试、死信或停止消费。

handler 接收 `DurableEventDelivery`，而不是裸 event envelope。delivery 包含 event、consumer group、partition、
offset、attempt、receivedAt 和 redelivered 等投递元数据，业务 handler 应使用这些信息实现幂等和诊断。

`event-stream-protobuf` 提供基于 `ProtobufMessageRegistry<String>` 的 protobuf codec。`encodeDurableEvent` 把
generated message 编码成 `DurableEventEnvelope`，事件类型使用 registry key；`publishProto` 和 `subscribeProto`
提供 publisher/consumer helper。protobuf codec 是独立模块，`event-stream-core` 不依赖 protobuf。

`event-stream-nats-jetstream` 是 NATS JetStream backend。它把 `EventStreamName` 映射为 NATS subject 和
JetStream stream name，使用 explicit ack；handler 成功返回后 ack，失败时根据 `DurableEventFailurePolicy`
执行 nak、term 或发布到 dead-letter stream。模块会把 `NatsJetStreamEventBus` 注册为 `DurableEventBus`、
`DurableEventPublisher` 和 `DurableEventConsumer`。
