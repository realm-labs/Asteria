package io.github.realmlabs.asteria.event

import java.io.Serializable

/**
 * Topic node used for event fan-out.
 *
 * A topic carries its parent chain, so publishing a leaf topic can also notify subscribers of every ancestor topic.
 */
class EventTopic private constructor(
    val name: String,
    val parent: EventTopic?,
) : Comparable<EventTopic>, Serializable {
    val path: String = parent?.let { "${it.path}.$name" } ?: name

    val segments: List<String> = parent?.segments.orEmpty() + name

    init {
        require(name.isValidEventTopicSegment()) { "invalid event topic segment $name" }
    }

    fun child(name: String): EventTopic {
        return EventTopic(name, this)
    }

    fun ancestors(): List<EventTopic> {
        return generateSequence(parent) { it.parent }.toList().asReversed()
    }

    fun ancestorsAndSelf(): List<EventTopic> {
        return ancestors() + this
    }

    override fun compareTo(other: EventTopic): Int {
        return path.compareTo(other.path)
    }

    override fun equals(other: Any?): Boolean {
        return other is EventTopic && other.path == path
    }

    override fun hashCode(): Int {
        return path.hashCode()
    }

    override fun toString(): String {
        return path
    }

    companion object {
        fun root(name: String): EventTopic {
            return EventTopic(name, null)
        }
    }
}

fun eventTopic(name: String): EventTopic {
    return EventTopic.root(name)
}

fun eventTopicPath(path: String): EventTopic {
    val segments = path.split('.').filter { it.isNotBlank() }
    require(segments.isNotEmpty()) { "event topic path must not be blank" }
    return segments.drop(1).fold(eventTopic(segments.first())) { parent, segment ->
        parent.child(segment)
    }
}

fun eventTopics(vararg topics: EventTopic): Set<EventTopic> {
    return topics.toCollection(linkedSetOf())
}

private fun String.isValidEventTopicSegment(): Boolean {
    return isNotBlank() && all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
