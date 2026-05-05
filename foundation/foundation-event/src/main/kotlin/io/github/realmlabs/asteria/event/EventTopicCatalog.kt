package io.github.realmlabs.asteria.event

/**
 * Base class for nested topic declarations.
 *
 * Catalogs keep topic definitions readable while still constructing normal [EventTopic] parent chains.
 */
open class EventTopicCatalog {
    val topic: EventTopic
    private val registry: EventTopicRegistry?

    constructor(name: String, registry: EventTopicRegistry? = EventTopicRegistry.global) {
        this.topic = eventTopic(name)
        this.registry = registry
        registry?.register(topic)
    }

    constructor(parent: EventTopicCatalog, name: String, registry: EventTopicRegistry? = parent.registry) {
        this.topic = parent.topic.child(name)
        this.registry = registry
        registry?.register(topic)
    }

    constructor(parent: EventTopic, name: String, registry: EventTopicRegistry? = EventTopicRegistry.global) {
        this.topic = parent.child(name)
        this.registry = registry
        registry?.register(topic)
    }

    fun event(name: String): EventTopic {
        return topic.child(name).also { registry?.register(it) }
    }
}

/**
 * Mutable topic catalog for startup registration and diagnostics.
 *
 * This type is intentionally not synchronized. Build topic catalogs during application startup, or keep one registry
 * owned by a single actor/runtime component.
 */
class EventTopicRegistry {
    private val topicsByPath = linkedMapOf<String, EventTopic>()

    fun register(topic: EventTopic): EventTopic {
        topic.ancestorsAndSelf().forEach { current ->
            val previous = topicsByPath.putIfAbsent(current.path, current)
            require(previous == null || previous.segments == current.segments) {
                "duplicate event topic path ${current.path}"
            }
        }
        return topic
    }

    fun find(path: String): EventTopic? {
        return topicsByPath[path]
    }

    fun childrenOf(topic: EventTopic): List<EventTopic> {
        return topicsByPath.values
            .filter { it.parent == topic }
            .sorted()
    }

    fun all(): List<EventTopic> {
        return topicsByPath.values.sorted()
    }

    companion object {
        val global = EventTopicRegistry()
    }
}
