# 事件系统

`foundation-event` 提供进程内事件分发，面向游戏业务里的领域事实。它适合 actor
内或服务内工作流：一次状态变更需要通知任务、红点、成就、战力失效、外观刷新等多个解耦系统。

事件不是命令。业务代码应该在状态已经变更后发布事实：

```kotlin
data class PlayerLevelChanged(
    val playerId: Long,
    val oldLevel: Int,
    val newLevel: Int,
) : GameEvent {
    override val topics = eventTopics(PlayerTopics.Progression.Level.Changed.topic)
}
```

## Topic Catalog

topic 是一棵树。叶子 topic 会保留 parent 链，所以订阅父 topic 的 handler 能收到子 topic 事件，业务代码不需要手动发布所有父节点。业务项目推荐用
`EventTopicCatalog` 定义运行时 topic，同时给 topic 节点加上 KSP 注解，供 handler 使用类型引用。

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

`PlayerTopics.Progression.Level.Changed.topic` 的路径是 `player.progression.level.changed`。发布它时会同时命中：

- `player`
- `player.progression`
- `player.progression.level`
- `player.progression.level.changed`

正常业务 topic 可以使用全局 registry。测试或生成代码如果需要隔离 topic 树，可以显式传入 `EventTopicRegistry`。registry
不做同步，应该在启动期构建完成，或者由单个
actor/runtime 组件持有。

## Handler 注册

业务项目推荐使用 `@AsteriaEventHandler` 声明 handler，再由 KSP 生成 dispatcher 和 registry。handler 复用
`foundation-message` 的 `HandlerContext` 风格，可以按具体事件类型订阅，也可以按 topic 订阅：

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

`@AsteriaEventHandler` 的字段含义：

- `dispatcher`：逻辑分发器 key，默认 `default`。不同 key 会生成不同 registry 和 dispatcher。
- `topicRefs`：推荐写法，引用被 `@AsteriaEventTopicRoot` / `@AsteriaEventTopic` 标记的 topic object。
- `topics`：直接写 topic path，适合少量外部 topic 字符串。
- `order`：同一个事件或 topic 下 handler 的执行顺序，数值小的先执行。

handler 必须定义唯一的 `handle(context, event, publisher)`。第一个参数决定 context 类型，第二个参数决定按事件类型注册还是按
topic 注册，第三个参数是可继续发布事件的 `EventPublisher<C>`。不配置 topic 的 handler 会按具体事件类型注册。配置了
`topicRefs` 或 `topics` 的 handler 会按 topic path 注册，并且 event 参数必须是 `GameEvent`，因为 topic 订阅可能收到声明了这个
topic 或其子 topic 的任意事件。同一个生成 dispatcher 里的 handler 必须使用同一种 context 类型。

KSP 会在 root package 的 `.generated` 包下生成：

- `Generated<Module>EventDispatchers`：暴露每个 dispatcher key 对应的 registry 和 dispatcher。
- `Generated<Module><Dispatcher>EventHandles` 及 chunk：保存静态 `EventHandle` 列表。
- `Generated<Module>EventTopicPaths`：保存从 topic 注解树推导出的字符串常量。
- `META-INF/asteria/codegen-snapshots/event/*.json`：记录本次扫描到的 topic 和 handler 模型，供可选校验使用。

如果 handler 包名类似 `com.example.game.handler.player`，root package 是 `com.example.game`；否则使用 handler 自己所在的包。

同步场景可以直接使用生成的 dispatcher：

```kotlin
val dispatcher = GeneratedGameEventDispatchers.default
val result = dispatcher.publish(context, PlayerLevelChanged(player.id, oldLevel, player.level))
```

没有订阅者是正常结果。`publish` 会返回 `EventDispatchResult`，里面包含命中的 topic、实际执行的 handler 和记录到的失败。

## Actor 队列分发

actor 中如果一个事件命中很多 handler，不建议在一次 receive 里把所有 handler 同步跑完。可以使用 `QueuedEventDispatcher`
把 handler 分成多次 pump 执行，让其他 actor 消息有机会插到两次事件处理之间：

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

`publish` 只负责匹配并排队 handler，返回 `EventPublishReceipt`。`pump` 才会实际执行 handler；如果队列里还有任务，
dispatcher 会再次调用 `schedulePump`。业务可以把 `maxHandlers` 调大，控制每次 actor receive 最多处理多少个事件
handler。队列 dispatcher 和同步 dispatcher 使用同一种 registry，因此 patch 后的 slot 规则一致。

## Patch 支持

KSP 生成的 `${dispatcher}Registry` 是 `PatchableEventHandleRegistry`。运行时补丁替换的是某一个 handler slot，
不是整个 dispatcher，也不是某个 topic 下的所有 handler。dispatcher 每次路由都会读取 registry 当前快照，所以同步分发和
actor 队列分发都会看到最新 handler。

```kotlin
class LevelPatch : RuntimePatchPlugin {
    override suspend fun install(context: RuntimePatchInstallContext) {
        context.eventHandlers.replaceEventType(
            GeneratedGameEventDispatchers.defaultRegistry,
            PlayerLevelChanged::class,
            key = eventHandleKey(PlayerQuestLevelHandler::class),
        ) { handlerContext, event, publisher ->
            handlerContext.player.quest.onLevelChanged(event.newLevel)
        }
    }
}
```

patch 的粒度是 `EventHandleKey`，不是 event type 或 topic。因为一个事件类型或一个 topic 下面通常会有多个 handler，直接按
topic
替换会误伤其他订阅者。KSP 生成的 key 默认由 `eventHandleKey(Handler::class)` 计算；如果同一个 handler 订阅多个 topic，使用
`eventHandleKey(Handler::class, topic)` 指定其中一个订阅 slot。补丁只能覆盖已有 slot，不能引入一个全新的事件订阅；卸载补丁后会回退到下一层补丁或基础
handler。

## KSP 注册

使用注解 handler 的模块需要接入 `foundation-event-ksp`：

```kotlin
dependencies {
    implementation("io.github.realm-labs.asteria:foundation-event:<version>")
    ksp("io.github.realm-labs.asteria:foundation-event-ksp:<version>")
}
```

`topicRefs` 是推荐写法，KSP 会从 `@AsteriaEventTopicRoot` / `@AsteriaEventTopic` 标记的 topic 树计算 path。`topics`
仍然保留给少量外部 topic 字符串使用。生成的 topic handle 会从 path 重建 topic，不依赖全局 registry 状态。

如果确实需要字符串常量，可以使用生成的 `Generated<Module>EventTopicPaths`：

```kotlin
val combatTopicPath = GeneratedGameEventTopicPaths.PlayerTopics.Combat.TOPIC
```

手写 `eventHandlers { ... }` 也可以使用，但更适合测试、示例或少量本地 handler；业务模块通常不需要维护集中式注册代码。

## 失败策略

默认策略是 fail-fast。同步分发时，handler 抛错会继续向发布方抛出；队列分发时，错误会在 `pump` 执行 handler
时抛出，并终止同一次事件分发 session 中尚未执行的 handler。

```kotlin
val dispatcher = EventDispatcher(
    registry,
    EventDispatchOptions(failurePolicy = EventDispatchFailurePolicy.CONTINUE),
)
```

非关键 fan-out 可以使用 `CONTINUE`，这样后续 handler 仍然会执行，失败记录在 dispatch result 或 pump result 中。

## 事件风暴保护

handler 可以通过传入的 `EventPublisher` 继续发布事件。由同一次根事件触发的嵌套发布会共享同一组分发预算，dispatcher
用两个参数限制这轮分发的规模：

```kotlin
EventDispatchOptions(
    maxNestedDepth = 32,
    maxPublishedEvents = 1024,
)
```

`maxNestedDepth` 限制嵌套发布深度，用来拦截直接或间接递归，比如 `A -> B -> A`。`maxPublishedEvents`
限制同一轮分发最多发布的事件数量，用来拦截不一定很深、但持续 fan-out 出更多事件的情况。超过任意限制时，dispatcher
会抛出 `EventDispatchLimitExceededException`。

业务 handler 应该避免发布可能回到自身依赖 topic 的派生事件。如果一个流程会产生大量细粒度变化，优先把派生状态标记为
dirty，并在 actor 当前处理轮次末尾统一重算，避免 handler 之间互相发布派生事件形成环。
