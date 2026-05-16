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

`MessageDispatcher` uses exact-type dispatch. By default it looks up the handler by the message runtime type.
Registering `BaseMessage` does not automatically handle subclasses; only the overload that receives an explicit
`messageType` looks up that supplied type. Missing handlers and handler failures are exposed to the caller.

For example, if only a `GameCommand` handler is registered, dispatching `MoveCommand` directly looks up
`MoveCommand::class` and does not find the `GameCommand` slot. Use the explicit-type overload only when the business
logic intentionally wants a group of subtypes to share one parent-interface handler:

```kotlin
sealed interface GameCommand

data class MoveCommand(val x: Int, val y: Int) : GameCommand

registry.register<GameCommand> { context, command ->
    context.commandBus.handle(command)
}

// Lookup key is MoveCommand::class; only GameCommand was registered, so this fails.
dispatcher.dispatch(context, MoveCommand(1, 2))

// Lookup key is GameCommand::class; this hits the parent-interface handler.
dispatcher.dispatch(context, GameCommand::class, MoveCommand(1, 2))
```

Use `foundation-message-ksp` and its Gradle plugin when handler registration would otherwise become large and manual.

```kotlin
plugins {
    id("io.github.realm-labs.asteria.message-codegen")
}

asteriaMessageCodegen {
    generatedPackage.set("com.example.game.generated")
    moduleId.set("game")
}
```

`@AsteriaMessageHandler(dispatcher = "...")` marks a handler class for generated registration. The handler must be a
non-abstract class and define `handle(context, message)`: the first parameter determines the dispatcher's
`HandlerContext` type, and the second parameter determines the registered message type. All handlers in one generated
module must share the same context type. `dispatcher` is a logical dispatcher key, useful for separating login-node,
game-node, internal-message, or other entry points into different registries.

KSP generates the following under `generatedPackage` and `moduleId`:

- `Generated<Module>NodeDispatchers`: exposes each dispatcher key's `*_HANDLES`, `*_REGISTRY`, and dispatcher.
- `Generated<Module><Dispatcher>MessageHandles` plus chunks: stores the static `MessageHandle` list.
- Optional `MessageCatalog`: generated when `messageCatalogEnabled` is on, for tooling and diagnostics rather than
  runtime routing.

The generated registry is a `PatchableMessageHandlerRegistry`. `MessageDispatcher` reads the current slot from the
registry on every dispatch: base slots come from KSP-generated handles, and patches replace one message-type slot
through
`context.messageHandlers.replace(registry, ...)`. Patches do not rebuild the dispatcher and cannot introduce a brand-new
message type;
after removal, the slot falls back to the next patch layer or the base handler. Code that needs a custom
`MessageHandleRegistry` can reuse the generated handles and construct its own `MessageDispatcher`.

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

The gateway protocol registry only owns protocol ids, protobuf types, parsers, and direction metadata. It enforces
direction: server-only messages cannot be decoded as client input, and client-only messages cannot be encoded as server
output. Message ids and message types must be unique.

Inbound protobuf messages also need a route registry. `clientMessage` and `bidirectionalMessage` register both the
protocol mapping and a `RouteTarget`; `serverMessage` only registers server output protocol metadata.
`ProtobufGatewayRouteResolver` resolves a decoded envelope to a route and can use an id resolver to fill
`GatewayRoute.entityId`; it does not run auth, rate limiting, or business handlers.

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
handle complete binary frames. Transport owns connection lifecycle, frame boundaries, and network backpressure behavior;
it does not own protocol id mapping, business routing, auth, or actor delivery.

If a project uses the built-in `BinaryGatewayPacket` header and protobuf envelope, provide a custom
`NettyGatewayPipelineInstaller` that combines `PacketCodec`, `NettyProtobufCodec`, and a gateway message handler.
`NettyProtobufCodec` only converts between frames and protobuf envelopes; route resolution remains in the
protocol/gateway dispatcher layer.

`GatewayMessageDispatcher` connects a route resolver with a forwarder: it receives an already decoded packet, resolves a
`GatewayRoute`, calls the forwarder, and returns the route for logs or metrics. It does not decode, retry, or manage the
session lifecycle.

`PekkoGatewayForwarder` is the `gateway-pekko` actor-runtime adapter. It can forward route results to entities,
singletons, service actors, or local handlers. `RouteTarget.GatewayLocal` requires a local handler, and
`PekkoGatewayMessageFactory` can wrap the raw packet into the actor message shape an application expects. Projects that
do not use Pekko can implement their own `GatewayForwarder` while keeping transport and actor topology decoupled.

## Ephemeral Broadcast

`ephemeral-broadcast-core` provides process-local `LocalEphemeralBroadcastBus`; `ephemeral-broadcast-pekko` extends
ephemeral broadcast to Pekko clusters; `ephemeral-broadcast-protobuf` adds protobuf payload helpers. Ephemeral broadcast
is suitable for online notifications, cache invalidation, and reload signals, not for durable business facts.

Ephemeral broadcast topics are opaque strings. Delivery is at-most-once, with no persistence, replay, or offline
compensation. Local subscribers run synchronously, so a slow subscriber delays later subscribers in the same publish
call.
Pekko ephemeral broadcast payloads must be serializable by the ActorSystem; protobuf broadcast should use
`ProtobufEphemeralBroadcastPayload` instead of directly broadcasting generated messages without serializers.

Durable business events such as payments, grants, mail, audit logs, and integration events belong to event streams,
not ephemeral broadcast. `event-stream-core` defines broker-neutral envelope, publisher, consumer, and delivery
contracts. Concrete backend modules provide persistence, acknowledgment, retry, dead-letter, and replay semantics. The
in-memory implementation is intended for local development and tests.

## Event Stream

`DurableEventEnvelope` represents a business fact that has happened and must be persisted. `payload` is raw bytes,
`type` is the stable event type, `key` is available for backend partitioning or ordered delivery, and `eventId`,
`correlationId`, and `causationId` support idempotency, trace correlation, and causal tracing.

`DurableEventPublisher.publish` returns a `DurableEventPublishResult` after the backend accepts the event. The result
can
include backend partition, offset, and publication time. It only means the event was accepted for publication; it does
not mean any consumer has processed it.

`DurableEventConsumer.subscribe` uses `DurableEventSubscribeOptions` to declare the consumer group, start position, and
failure policy. Start position can be latest, earliest, a timestamp, or an offset. The default delivery contract is
at-least-once; a backend should acknowledge or commit progress only after the handler returns successfully. If the
handler throws, the backend applies `DurableEventFailurePolicy` and its own retry, dead-letter, or stop configuration.

Handlers receive `DurableEventDelivery`, not a bare event envelope. The delivery carries event, consumer group,
partition, offset, attempt, receivedAt, and redelivered metadata so business handlers can implement idempotency and
diagnostics.

`event-stream-protobuf` provides protobuf codecs backed by `ProtobufMessageRegistry<String>`. `encodeDurableEvent`
encodes a generated message into a `DurableEventEnvelope` and uses the registry key as the event type. `publishProto`
and `subscribeProto` provide publisher and consumer helpers. The protobuf codec is a separate module; `event-stream-core`
does not depend on protobuf.

`event-stream-nats-jetstream` is the NATS JetStream backend. It maps `EventStreamName` to a NATS subject and JetStream
stream name and uses explicit acknowledgments. A message is acked after the handler returns successfully; failures apply
`DurableEventFailurePolicy` as nak, term, or publication to a dead-letter stream. The module registers
`NatsJetStreamEventBus` as `DurableEventBus`, `DurableEventPublisher`, and `DurableEventConsumer`.
