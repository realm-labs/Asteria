package io.github.realmlabs.asteria.game.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * A half-open instant range: `[start, end)`.
 *
 * Half-open ranges avoid double-counting boundary instants when multiple activity windows are adjacent.
 */
data class GameTimeRange(
    /**
     * Inclusive start instant.
     */
    val start: Instant,
    /**
     * Exclusive end instant.
     */
    val end: Instant,
) {
    init {
        require(end > start) { "game time range end must be after start" }
    }

    /**
     * Returns true when [instant] is inside `[start, end)`.
     */
    fun contains(instant: Instant): Boolean {
        return !instant.isBefore(start) && instant.isBefore(end)
    }

    /**
     * Returns true when this range and [other] share at least one instant.
     */
    fun overlaps(other: GameTimeRange): Boolean {
        return start < other.end && other.start < end
    }
}

/**
 * A daily recurring half-open local-time range.
 *
 * A range may cross midnight. For example, `22:00..02:00` matches 23:00 today and 01:00 tomorrow.
 */
data class DailyTimeWindow(
    /**
     * Inclusive local start time.
     */
    val start: LocalTime,
    /**
     * Exclusive local end time.
     */
    val end: LocalTime,
) {
    init {
        require(start != end) { "daily time window start and end must be different" }
    }

    /**
     * Returns true when [instant] is inside this recurring window in [rule]'s zone.
     */
    fun contains(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
    ): Boolean {
        val time = instant.atZone(rule.zoneId).toLocalTime()
        return if (start < end) {
            !time.isBefore(start) && time.isBefore(end)
        } else {
            !time.isBefore(start) || time.isBefore(end)
        }
    }
}

/**
 * A weekly recurring half-open local-time range.
 *
 * This is useful for activities such as "every Friday 20:00 to Sunday 23:59".
 */
data class WeeklyTimeWindow(
    /**
     * Inclusive start day.
     */
    val startDay: DayOfWeek,
    /**
     * Inclusive local start time.
     */
    val startTime: LocalTime,
    /**
     * Exclusive end day.
     */
    val endDay: DayOfWeek,
    /**
     * Exclusive local end time.
     */
    val endTime: LocalTime,
) {
    init {
        require(startDay != endDay || startTime != endTime) {
            "weekly time window start and end must be different"
        }
    }

    /**
     * Returns true when [instant] is inside this recurring weekly window in [rule]'s zone.
     */
    fun contains(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
    ): Boolean {
        val local = instant.atZone(rule.zoneId)
        val point = local.dayOfWeek.weekMinute(local.toLocalTime())
        val start = startDay.weekMinute(startTime)
        val end = endDay.weekMinute(endTime)
        return if (start < end) {
            point in start until end
        } else {
            point >= start || point < end
        }
    }
}

/**
 * Builds an instant range for one logical game day.
 */
fun gameDayRange(
    gameDay: LocalDate,
    rule: GameDayRule = GameDayRule(),
): GameTimeRange {
    val start = GameTime.startOfGameDay(gameDay, rule).toInstant()
    val end = GameTime.startOfGameDay(gameDay.plusDays(1), rule).toInstant()
    return GameTimeRange(start, end)
}

/**
 * Builds an instant range for the logical game day containing [instant].
 */
fun currentGameDayRange(
    instant: Instant,
    rule: GameDayRule = GameDayRule(),
): GameTimeRange {
    return gameDayRange(GameTime.gameDayOf(instant, rule), rule)
}

private fun DayOfWeek.weekMinute(time: LocalTime): Int {
    return (value - 1) * MINUTES_PER_DAY + time.hour * 60 + time.minute
}

private const val MINUTES_PER_DAY: Int = 24 * 60
