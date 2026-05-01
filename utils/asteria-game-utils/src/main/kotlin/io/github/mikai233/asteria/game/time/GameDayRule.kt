package io.github.mikai233.asteria.game.time

import java.time.LocalTime
import java.time.ZoneId

/**
 * Defines how a game maps real clock time into business days.
 *
 * Many games do not reset at calendar midnight. For example, a game with `dayStart = 05:00` treats
 * `2026-05-01T04:59` as part of the previous game day and `2026-05-01T05:00` as the first instant of the new game day.
 */
data class GameDayRule(
    /**
     * Time zone used for all local date and local time calculations.
     */
    val zoneId: ZoneId = ZoneId.systemDefault(),
    /**
     * Local time at which a logical game day starts.
     */
    val dayStart: LocalTime = LocalTime.MIDNIGHT,
) {
    init {
        require(dayStart.nano == 0) { "game day start must not contain nanoseconds" }
    }
}
