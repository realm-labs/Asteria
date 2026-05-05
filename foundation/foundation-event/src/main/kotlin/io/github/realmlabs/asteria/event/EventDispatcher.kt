package io.github.realmlabs.asteria.event

import io.github.realmlabs.asteria.message.HandlerContext
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

fun interface EventHandler<C : HandlerContext, in E : GameEvent> {
    fun handle(context: C, event: E, publisher: EventPublisher<C>)
}

interface EventPublisher<C : HandlerContext> {
    fun publish(event: GameEvent): EventPublishReceipt
}

class EventHandle<C : HandlerContext> private constructor(
    val eventType: KClass<out GameEvent>?,
    val topic: EventTopic?,
    val order: Int,
    private val dispatch: (C, GameEvent, EventPublisher<C>) -> Unit,
) {
    fun invoke(context: C, event: GameEvent, publisher: EventPublisher<C>) {
        dispatch(context, event, publisher)
    }

    companion object {
        fun <C : HandlerContext, E : GameEvent> forEventType(
            eventType: KClass<E>,
            order: Int = 0,
            handler: EventHandler<C, E>,
        ): EventHandle<C> {
            return EventHandle(eventType, topic = null, order = order) { context, event, publisher ->
                @Suppress("UNCHECKED_CAST")
                handler.handle(context, event as E, publisher)
            }
        }

        fun <C : HandlerContext> forTopic(
            topic: EventTopic,
            order: Int = 0,
            handler: EventHandler<C, GameEvent>,
        ): EventHandle<C> {
            return EventHandle(eventType = null, topic = topic, order = order) { context, event, publisher ->
                handler.handle(context, event, publisher)
            }
        }
    }
}

interface EventHandleRegistry<C : HandlerContext> {
    fun handlersFor(eventType: KClass<out GameEvent>): List<EventHandle<C>>

    fun handlersFor(topic: EventTopic): List<EventHandle<C>>

    fun all(): List<EventHandle<C>>
}

class DefaultEventHandleRegistry<C : HandlerContext>(
    handles: Iterable<EventHandle<C>> = emptyList(),
) : EventHandleRegistry<C> {
    private val handlersByEventType = handles
        .filter { it.eventType != null }
        .groupBy { requireNotNull(it.eventType) }
        .mapValues { it.value.sortedBy(EventHandle<C>::order) }
    private val handlersByTopic = handles
        .filter { it.topic != null }
        .groupBy { requireNotNull(it.topic) }
        .mapValues { it.value.sortedBy(EventHandle<C>::order) }
    private val all = handles.sortedBy(EventHandle<C>::order)

    override fun handlersFor(eventType: KClass<out GameEvent>): List<EventHandle<C>> {
        return handlersByEventType[eventType].orEmpty()
    }

    override fun handlersFor(topic: EventTopic): List<EventHandle<C>> {
        return handlersByTopic[topic].orEmpty()
    }

    override fun all(): List<EventHandle<C>> {
        return all
    }
}

class EventHandleRegistryBuilder<C : HandlerContext> {
    private val handles = mutableListOf<EventHandle<C>>()

    fun <E : GameEvent> on(
        eventType: KClass<E>,
        order: Int = 0,
        handler: EventHandler<C, E>,
    ) {
        handles += EventHandle.forEventType(eventType, order, handler)
    }

    inline fun <reified E : GameEvent> on(
        order: Int = 0,
        handler: EventHandler<C, E>,
    ) {
        on(E::class, order, handler)
    }

    fun onTopic(
        topic: EventTopic,
        order: Int = 0,
        handler: EventHandler<C, GameEvent>,
    ) {
        handles += EventHandle.forTopic(topic, order, handler)
    }

    fun build(): DefaultEventHandleRegistry<C> {
        return DefaultEventHandleRegistry(handles)
    }
}

fun <C : HandlerContext> eventHandlers(
    configure: EventHandleRegistryBuilder<C>.() -> Unit,
): DefaultEventHandleRegistry<C> {
    return EventHandleRegistryBuilder<C>().apply(configure).build()
}

enum class EventDispatchFailurePolicy {
    FAIL_FAST,
    CONTINUE,
}

data class EventDispatchOptions(
    val failurePolicy: EventDispatchFailurePolicy = EventDispatchFailurePolicy.FAIL_FAST,
    val maxNestedDepth: Int = 32,
    val maxPublishedEvents: Int = 1024,
) {
    init {
        require(maxNestedDepth > 0) { "event dispatch maxNestedDepth must be positive" }
        require(maxPublishedEvents > 0) { "event dispatch maxPublishedEvents must be positive" }
    }
}

class EventDispatchLimitExceededException(
    message: String,
) : IllegalStateException(message)

private class EventDispatchSession(
    val id: Long,
) {
    var publishedEvents: Int = 0
    val stack: MutableList<GameEvent> = mutableListOf()
    var aborted: Boolean = false
}

data class EventHandlerFailure<C : HandlerContext>(
    val handle: EventHandle<C>,
    val error: Throwable,
)

data class EventPublishReceipt(
    val sessionId: Long,
    val event: GameEvent,
    val matchedTopics: Set<EventTopic>,
    val scheduledHandlers: Int,
)

data class EventDispatchResult<C : HandlerContext>(
    val receipt: EventPublishReceipt,
    val event: GameEvent,
    val matchedTopics: Set<EventTopic>,
    val invokedHandlers: List<EventHandle<C>>,
    val failures: List<EventHandlerFailure<C>>,
)

data class EventRoute<C : HandlerContext>(
    val event: GameEvent,
    val matchedTopics: Set<EventTopic>,
    val handlers: List<EventHandle<C>>,
)

class EventRouter<C : HandlerContext>(
    private val handles: EventHandleRegistry<C>,
) {
    fun route(event: GameEvent): EventRoute<C> {
        val matchedTopics = event.topics
            .flatMap(EventTopic::ancestorsAndSelf)
            .toCollection(linkedSetOf())
        val matchedHandlers = (handles.handlersFor(event::class) + matchedTopics.flatMap(handles::handlersFor))
            .distinctByIdentity()
            .sortedBy(EventHandle<C>::order)
        return EventRoute(
            event = event,
            matchedTopics = matchedTopics,
            handlers = matchedHandlers,
        )
    }
}

class EventDispatcher<C : HandlerContext>(
    handles: EventHandleRegistry<C>,
    private val options: EventDispatchOptions = EventDispatchOptions(),
) {
    private val logger = LoggerFactory.getLogger(EventDispatcher::class.java)
    private val router = EventRouter(handles)
    private var nextSessionId: Long = 0

    fun publish(context: C, event: GameEvent): EventDispatchResult<C> {
        return publish(context, event, EventDispatchSession(nextSessionId()))
    }

    private fun publish(context: C, event: GameEvent, session: EventDispatchSession): EventDispatchResult<C> {
        enterStack(session, event)
        try {
            return dispatch(context, router.route(event), ScopedEventPublisher(context, session), session.id)
        } finally {
            session.stack.removeLast()
        }
    }

    private fun dispatch(
        context: C,
        route: EventRoute<C>,
        publisher: EventPublisher<C>,
        sessionId: Long,
    ): EventDispatchResult<C> {
        val failures = mutableListOf<EventHandlerFailure<C>>()

        route.handlers.forEach { handle ->
            try {
                handle.invoke(context, route.event, publisher)
            } catch (error: Throwable) {
                logFailure(context, route.event, handle, error)
                if (options.failurePolicy == EventDispatchFailurePolicy.FAIL_FAST) {
                    throw error
                }
                failures += EventHandlerFailure(handle, error)
            }
        }

        val receipt = EventPublishReceipt(
            sessionId = sessionId,
            event = route.event,
            matchedTopics = route.matchedTopics,
            scheduledHandlers = route.handlers.size,
        )
        return EventDispatchResult(
            receipt = receipt,
            event = route.event,
            matchedTopics = route.matchedTopics,
            invokedHandlers = route.handlers,
            failures = failures,
        )
    }

    private fun enterStack(session: EventDispatchSession, event: GameEvent) {
        val nextStack = session.stack + event
        enterPath(session, nextStack, options)
        session.stack += event
    }

    private fun logFailure(context: C, event: GameEvent, handle: EventHandle<C>, error: Throwable) {
        logger.error(
            "event dispatch failed runtime={} event={} topic={}",
            context.runtime.name,
            event::class.qualifiedName,
            handle.topic,
            error,
        )
    }

    private inner class ScopedEventPublisher(
        private val context: C,
        private val session: EventDispatchSession,
    ) : EventPublisher<C> {
        override fun publish(event: GameEvent): EventPublishReceipt {
            return this@EventDispatcher.publish(context, event, session).receipt
        }
    }

    private fun nextSessionId(): Long {
        nextSessionId += 1
        return nextSessionId
    }
}

data class EventPumpResult<C : HandlerContext>(
    val invokedHandlers: List<EventHandle<C>>,
    val failures: List<EventHandlerFailure<C>>,
    val remainingHandlers: Int,
)

class QueuedEventDispatcher<C : HandlerContext>(
    handles: EventHandleRegistry<C>,
    private val options: EventDispatchOptions = EventDispatchOptions(),
    private val schedulePump: () -> Unit,
) {
    private val logger = LoggerFactory.getLogger(QueuedEventDispatcher::class.java)
    private val router = EventRouter(handles)
    private var nextSessionId: Long = 0
    private var queue = ArrayDeque<QueuedEventHandlerTask<C>>()
    private var pumpScheduled: Boolean = false
    private var runningPump: Boolean = false

    fun publish(context: C, event: GameEvent): EventPublishReceipt {
        return enqueueEvent(context, event, EventDispatchSession(nextSessionId()), emptyList())
    }

    fun pump(maxHandlers: Int = 1): EventPumpResult<C> {
        require(maxHandlers > 0) { "event pump maxHandlers must be positive" }
        pumpScheduled = false
        runningPump = true
        val invokedHandlers = mutableListOf<EventHandle<C>>()
        val failures = mutableListOf<EventHandlerFailure<C>>()
        var errorToThrow: Throwable? = null
        try {
            while (invokedHandlers.size < maxHandlers && queue.isNotEmpty()) {
                val task = queue.removeFirst()
                if (task.session.aborted) {
                    continue
                }
                invokedHandlers += task.handle
                try {
                    task.handle.invoke(
                        task.context,
                        task.event,
                        QueuedEventPublisher(task.context, task.session, task.path),
                    )
                } catch (error: Throwable) {
                    logFailure(task.context, task.event, task.handle, error)
                    failures += EventHandlerFailure(task.handle, error)
                    if (options.failurePolicy == EventDispatchFailurePolicy.FAIL_FAST) {
                        task.session.aborted = true
                        discardSessionTasks(task.session)
                        errorToThrow = error
                        break
                    }
                }
            }
        } finally {
            runningPump = false
            if (queue.isNotEmpty()) {
                requestPump()
            }
        }
        errorToThrow?.let { throw it }
        return EventPumpResult(
            invokedHandlers = invokedHandlers,
            failures = failures,
            remainingHandlers = queue.size,
        )
    }

    fun pendingHandlers(): Int {
        return queue.size
    }

    private fun enqueueEvent(
        context: C,
        event: GameEvent,
        session: EventDispatchSession,
        parentPath: List<GameEvent>,
    ): EventPublishReceipt {
        val path = parentPath + event
        enterPath(session, path, options)
        val route = router.route(event)
        route.handlers.forEach { handle ->
            queue += QueuedEventHandlerTask(
                context = context,
                event = event,
                handle = handle,
                session = session,
                path = path,
            )
        }
        if (route.handlers.isNotEmpty()) {
            requestPump()
        }
        return EventPublishReceipt(
            sessionId = session.id,
            event = event,
            matchedTopics = route.matchedTopics,
            scheduledHandlers = route.handlers.size,
        )
    }

    private fun requestPump() {
        if (!pumpScheduled && !runningPump) {
            pumpScheduled = true
            schedulePump()
        }
    }

    private fun discardSessionTasks(session: EventDispatchSession) {
        queue = ArrayDeque(queue.filterNot { it.session === session })
    }

    private fun nextSessionId(): Long {
        nextSessionId += 1
        return nextSessionId
    }

    private fun logFailure(context: C, event: GameEvent, handle: EventHandle<C>, error: Throwable) {
        logger.error(
            "queued event dispatch failed runtime={} event={} topic={}",
            context.runtime.name,
            event::class.qualifiedName,
            handle.topic,
            error,
        )
    }

    private inner class QueuedEventPublisher(
        private val context: C,
        private val session: EventDispatchSession,
        private val path: List<GameEvent>,
    ) : EventPublisher<C> {
        override fun publish(event: GameEvent): EventPublishReceipt {
            return enqueueEvent(context, event, session, path)
        }
    }
}

private data class QueuedEventHandlerTask<C : HandlerContext>(
    val context: C,
    val event: GameEvent,
    val handle: EventHandle<C>,
    val session: EventDispatchSession,
    val path: List<GameEvent>,
)

private fun enterPath(
    session: EventDispatchSession,
    path: List<GameEvent>,
    options: EventDispatchOptions,
) {
    val depth = path.size
    if (depth > options.maxNestedDepth) {
        throw EventDispatchLimitExceededException(
            "event dispatch nested depth exceeded ${options.maxNestedDepth}: ${path.path()}",
        )
    }
    val publishedEvents = session.publishedEvents + 1
    if (publishedEvents > options.maxPublishedEvents) {
        throw EventDispatchLimitExceededException(
            "event dispatch published event limit exceeded ${options.maxPublishedEvents}: ${path.path()}",
        )
    }
    session.publishedEvents = publishedEvents
}

private fun List<GameEvent>.path(): String {
    return joinToString(" -> ") { event ->
        event::class.qualifiedName ?: event::class.simpleName ?: "unknown"
    }
}

private fun <T : Any> Iterable<T>.distinctByIdentity(): List<T> {
    val seen = java.util.IdentityHashMap<T, Unit>()
    val result = mutableListOf<T>()
    for (value in this) {
        if (seen.put(value, Unit) == null) {
            result += value
        }
    }
    return result
}
