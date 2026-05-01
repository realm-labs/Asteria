package io.github.mikai233.asteria.game.time

import java.time.Duration
import java.time.Instant

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
        require(!cooldown.isNegative) { "cooldown must not be negative" }
        return lastUsedAt == null || !readyAt(lastUsedAt, cooldown).isAfter(now)
    }

    /**
     * Returns the first instant at which an operation can run again.
     */
    fun readyAt(
        lastUsedAt: Instant,
        cooldown: Duration,
    ): Instant {
        require(!cooldown.isNegative) { "cooldown must not be negative" }
        return lastUsedAt.plus(cooldown)
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
        require(!cooldown.isNegative) { "cooldown must not be negative" }
        if (lastUsedAt == null) {
            return Duration.ZERO
        }
        val remaining = Duration.between(now, readyAt(lastUsedAt, cooldown))
        return if (remaining.isNegative) Duration.ZERO else remaining
    }
}
