package io.github.realmlabs.asteria.game.time

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Small helpers for checking timestamp-based cooldowns.
 */
object Cooldown {
    /**
     * Returns true when [lastUsedAt] is null or [cooldown] has elapsed by [now].
     */
    fun isReady(
        lastUsedAt: Instant?,
        now: Instant,
        cooldown: Duration,
    ): Boolean {
        require(cooldown >= Duration.ZERO) { "cooldown must not be negative" }
        return lastUsedAt == null || readyAt(lastUsedAt, cooldown) <= now
    }

    /**
     * Returns the first instant at which an operation can run again.
     */
    fun readyAt(
        lastUsedAt: Instant,
        cooldown: Duration,
    ): Instant {
        require(cooldown >= Duration.ZERO) { "cooldown must not be negative" }
        return lastUsedAt + cooldown
    }

    /**
     * Returns the remaining cooldown duration at [now].
     *
     * The result is never negative. A null [lastUsedAt] means the operation is immediately ready.
     */
    fun remaining(
        lastUsedAt: Instant?,
        now: Instant,
        cooldown: Duration,
    ): Duration {
        require(cooldown >= Duration.ZERO) { "cooldown must not be negative" }
        if (lastUsedAt == null) {
            return Duration.ZERO
        }
        val remaining = readyAt(lastUsedAt, cooldown) - now
        return if (remaining < Duration.ZERO) Duration.ZERO else remaining
    }
}
