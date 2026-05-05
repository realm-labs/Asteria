package io.github.realmlabs.asteria.event

/**
 * Business fact published after state has changed.
 *
 * Implementations should describe what happened. The [topics] set describes which dependency scopes are affected.
 */
interface GameEvent {
    val topics: Set<EventTopic>
        get() = emptySet()
}
