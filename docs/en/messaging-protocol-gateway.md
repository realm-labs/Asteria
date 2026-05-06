# Messaging, Protocol, and Gateway

These modules separate business messages, protocol ids, and client connections. A project can use only message dispatch,
or combine it with protobuf protocol generation and a Netty gateway.

## Business Messages and Handlers

`foundation-message`'s `Message` is only a routing marker. Serialization and delivery are decided by the concrete
runtime.

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

`MessageDispatcher` uses exact-type dispatch. Registering `BaseMessage` does not automatically handle subclasses. Use
`foundation-message-ksp` and its Gradle plugin when handler registration would otherwise become large and manual.

```kotlin
plugins {
    id("io.github.realm-labs.asteria.message-codegen")
}

asteriaMessageCodegen {
    generatedPackage.set("com.example.game.generated")
    moduleId.set("game")
}
```

After a handler class is annotated with `@AsteriaMessageHandler`, KSP generates the catalog and dispatchers under the
configured package and module id. A handler must define a `handle(context, message)` method.

## Protobuf Protocol Generation

`protocol-protobuf` provides the gateway protocol registry; `rpc-protobuf` provides the RPC registry. `protobuf-codegen`
generates Kotlin registry files from protobuf descriptors and metadata.

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

Large generated protocols are split into `GeneratedGatewayProtocolChunkN` and `GeneratedRpcProtocolChunkN`; the main
file only aggregates chunks.

Gateway protocol enforces direction: server-only messages cannot be decoded as client input, and client-only messages
cannot be encoded as server output. Message ids and message types must be unique. Inbound messages that should reach
business code must have a route.

## Pekko RPC

`rpc-protobuf-pekko` provides an explicit serializer for registered protobuf RPC messages. Cross-node messages must have
stable ids in the registry; otherwise rolling upgrades and mixed versions cannot deserialize safely.

Entity RPC also needs an entity-id registry or shard extractor that can read the target entity id from the message.

RPC id space is separate from gateway protocol id space. The Pekko serializer only handles registered `GeneratedMessage`
instances; the sharding extractor fails when a message has no entity-id resolver.

## Gateway Session

`gateway-core`'s `GatewaySession` represents connection state only. It does not define login, player binding, or
distributed session ownership.

```kotlin
val PlayerIdKey = GatewaySessionAttributeKey<Long>("playerId")

session.set(PlayerIdKey, playerId)
val playerId = session.get(PlayerIdKey)

session.write(GatewayFrame(payload))
session.close(GatewayCloseReason.Application)
```

Authentication, login replacement, player-to-session indexes, and cross-node kicks belong in business code or a
GM/cluster adapter.

## Netty Transport

`gateway-netty` provides binary TCP and WebSocket transports:

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

`GatewayTransportHandler` receives connect, frame, and disconnect events. The default TCP/WebSocket pipelines only
handle complete binary frames. If a project uses the built-in `BinaryGatewayPacket` header and protobuf envelope,
provide a custom `NettyGatewayPipelineInstaller` that combines `PacketCodec`, `NettyProtobufCodec`, and a gateway
message handler.

`PekkoGatewayForwarder` can forward route results to entities, singletons, service actors, or local handlers.
`RouteTarget.GatewayLocal` requires a local handler. `GatewayMessageDispatcher` itself does not decode or retry; it only
resolves and forwards.

## Broadcast

`broadcast-core` provides process-local `LocalBroadcastBus`; `broadcast-pekko` extends broadcast to Pekko clusters;
`broadcast-protobuf` adds protobuf payload helpers. Broadcast is suitable for notifications, not for RPC-like flows that
require ack and retry.

Broadcast topics are opaque strings. Delivery is at-most-once. Local subscribers run synchronously, so a slow subscriber
delays later subscribers in the same publish call. Pekko broadcast payloads must be serializable by the ActorSystem;
protobuf broadcast should use `ProtobufBroadcastPayload` instead of directly broadcasting generated messages without
serializers.
