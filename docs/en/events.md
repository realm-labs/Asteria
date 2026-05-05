# Events

`foundation-event` provides an in-process event dispatcher for game-domain facts. It is intended for actor-local or
service-local workflows where one state change should notify several decoupled systems, such as quests, red points,
achievement checks, combat power invalidation, or appearance refresh.

Events are not commands. Business code should publish facts after state has changed:

```kotlin
data class PlayerLevelChanged(
    val playerId: Long,
    val oldLevel: Int,
    val newLevel: Int,
) : GameEvent {
    override val topics = eventTopics(PlayerTopics.Progression.Level.Changed.topic)
}
```

## Topic Catalogs

Topics form a tree. A leaf topic keeps its parent chain, so subscribers of parent topics receive child events without
business code manually publishing every parent. Business modules should define runtime topics with `EventTopicCatalog`
and annotate the topic nodes so KSP can resolve type-safe topic references from handlers.

```kotlin
@AsteriaEventTopicRoot("player")
object PlayerTopics : EventTopicCatalog("player") {
    @AsteriaEventTopic("progression")
    object Progression : EventTopicCatalog(PlayerTopics, "progression") {
        @AsteriaEventTopic("level")
        object Level : EventTopicCatalog(Progression, "level") {
            @AsteriaEventTopic("changed")
            object Changed : EventTopicCatalog(Level, "changed")
        }
    }

    @AsteriaEventTopic("combat")
    object Combat : EventTopicCatalog(PlayerTopics, "combat")
}
```

`PlayerTopics.Progression.Level.Changed.topic` has the path `player.progression.level.changed`. Publishing it also matches:

- `player`
- `player.progression`
- `player.progression.level`
- `player.progression.level.changed`

Use the global registry for normal application topics. Tests or generated catalogs can pass an explicit
`EventTopicRegistry` when they need an isolated tree. The registry is not synchronized; build catalogs during startup or
keep a registry owned by one actor/runtime component.

## Handler Registration

Business modules should usually declare handlers with `@AsteriaEventHandler` and let KSP generate the dispatcher and
registry. Handlers reuse the same `HandlerContext` style as `foundation-message` and can subscribe by concrete event
type or by topic:

```kotlin
@AsteriaEventHandler
class PlayerQuestLevelHandler {
    fun handle(
        context: PlayerHandlerContext,
        event: PlayerLevelChanged,
        publisher: EventPublisher<PlayerHandlerContext>,
    ) {
        context.player.quest.onLevelChanged(event.newLevel)
    }
}

@AsteriaEventHandler(topicRefs = [PlayerTopics.Combat::class], order = 100)
class PlayerCombatDirtyHandler {
    fun handle(
        context: PlayerHandlerContext,
        event: GameEvent,
        publisher: EventPublisher<PlayerHandlerContext>,
    ) {
        context.player.combatPower.markDirty()
    }
}
```

Handlers without a topic are registered by concrete event type. Handlers with `topicRefs` or `topics` are registered by
topic path and must accept `GameEvent`, because a topic subscription can receive any event that declares that topic or
one of its children.

The processor generates `Generated<Module>EventDispatchers`, `Generated<Module>EventTopicPaths`, and chunked handle
lists under the root package. A handler package such as `com.example.game.handler.player` uses `com.example.game` as
the root; otherwise the handler's own package is used. Handlers in one generated dispatcher must share the same context
type.

Synchronous code can use the generated dispatcher directly:

```kotlin
val dispatcher = GeneratedGameEventDispatchers.default
val result = dispatcher.publish(context, PlayerLevelChanged(player.id, oldLevel, player.level))
```

No subscribers is a normal result. The dispatcher returns `EventDispatchResult` with matched topics, invoked handlers,
and any recorded failures.

## Actor Queues

When one event matches many handlers inside an actor, avoid running the entire fan-out in one receive. Use
`QueuedEventDispatcher` to split handler execution across pump messages, so other actor messages can run between event
handler batches:

```kotlin
private object EventPump

private val events = QueuedEventDispatcher(
    GeneratedGameEventDispatchers.defaultRegistry,
    schedulePump = { self.tell(EventPump, self) },
)

override fun createReceive(): Receive {
    return receiveBuilder()
        .match(PlayerLevelChangedCommand::class.java) { command ->
            events.publish(handlerContext, PlayerLevelChanged(command.playerId, command.oldLevel, command.newLevel))
        }
        .match(EventPump::class.java) {
            events.pump(maxHandlers = 1)
        }
        .build()
}
```

`publish` only matches and queues handlers, returning `EventPublishReceipt`. `pump` runs handlers. If work remains after
a pump, the dispatcher calls `schedulePump` again. Increase `maxHandlers` to control how many event handlers one actor
receive may run.

## Patch Support

The generated `${dispatcher}Registry` is a `PatchableEventHandleRegistry`. Runtime patches can replace one handler slot;
dispatchers read the registry's current snapshot during routing, so both synchronous dispatch and actor queued dispatch
see the latest handler.

```kotlin
class LevelPatch : RuntimePatchPlugin {
    override suspend fun install(context: PatchInstallContext) {
        context.replaceEventTypeHandler(
            GeneratedGameEventDispatchers.defaultRegistry,
            PlayerLevelChanged::class,
            key = eventHandleKey(PlayerQuestLevelHandler::class),
        ) { handlerContext, event, publisher ->
            handlerContext.player.quest.onLevelChanged(event.newLevel)
        }
    }
}
```

The patch key is an `EventHandleKey`, not an event type or topic. One event type or topic usually has multiple
handlers, so replacing by topic would affect unrelated subscribers. KSP-generated keys are computed with
`eventHandleKey(Handler::class)`; when one handler subscribes to multiple topics, use
`eventHandleKey(Handler::class, topic)` to target one subscription slot.

## KSP Registration

Modules that use annotated handlers need `foundation-event-ksp`:

```kotlin
dependencies {
    implementation("io.github.realm-labs.asteria:foundation-event:<version>")
    ksp("io.github.realm-labs.asteria:foundation-event-ksp:<version>")
}
```

`topicRefs` is the recommended form. KSP resolves paths from topic trees marked with `@AsteriaEventTopicRoot` /
`@AsteriaEventTopic`. `topics` remains available for the rare case where a handler must subscribe to an external topic
string. Generated topic handles rebuild the topic from the path, so they do not depend on global registry state.

When a string constant is needed, use the generated `Generated<Module>EventTopicPaths`:

```kotlin
val combatTopicPath = GeneratedGameEventTopicPaths.PlayerTopics.Combat.TOPIC
```

Manual `eventHandlers { ... }` registration is still available, but it is mainly useful for tests, examples, or small
local dispatchers. Normal business modules should not need centralized registration code.

## Failure Policy

By default, handler errors are fail-fast. In synchronous dispatch, the error propagates to the publisher. In queued
dispatch, the error is thrown by `pump` when the handler runs, and the remaining handlers in the same event dispatch
session are aborted.

```kotlin
val dispatcher = EventDispatcher(
    registry,
    EventDispatchOptions(failurePolicy = EventDispatchFailurePolicy.CONTINUE),
)
```

Use `CONTINUE` for non-critical fan-out where later handlers should still run. Failures are recorded in the dispatch
result or pump result.

## Storm Protection

Handlers may publish more events through the `EventPublisher` passed to them. Nested events triggered by one root event
share the same dispatch budget. The dispatcher limits that dispatch run with two options:

```kotlin
EventDispatchOptions(
    maxNestedDepth = 32,
    maxPublishedEvents = 1024,
)
```

`maxNestedDepth` limits nested publish depth and catches direct or indirect recursion, such as `A -> B -> A`.
`maxPublishedEvents` limits how many events can be published in one dispatch run and catches broad fan-out that keeps
producing more events without growing the stack deeply. When either limit is exceeded, dispatch fails with
`EventDispatchLimitExceededException`.

Business handlers should still avoid publishing derived events that can loop back into their own dependency topic.
Prefer marking derived state dirty and recalculating once at the end of the actor turn when a workflow can produce many
small changes.
