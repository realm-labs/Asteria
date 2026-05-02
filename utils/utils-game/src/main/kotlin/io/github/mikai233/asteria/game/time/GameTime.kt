package io.github.mikai233.asteria.game.time

import java.time.*
import java.time.temporal.ChronoUnit

/**
 * Helpers for game time calculations that are based on a configurable logical day start.
 *
 * All calculations use the [GameDayRule.zoneId] and [GameDayRule.dayStart] supplied by the caller. Use these helpers
 * instead of calendar midnight when a game resets at a logical time such as 05:00.
 */
object GameTime {
    /**
     * Returns the logical game day containing [instant].
     *
     * If [GameDayRule.dayStart] is 05:00, local `04:59` belongs to the previous game day.
     */
    fun gameDayOf(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
    ): LocalDate {
        val local = instant.atZone(rule.zoneId)
        return if (local.toLocalTime() < rule.dayStart) {
            local.toLocalDate().minusDays(1)
        } else {
            local.toLocalDate()
        }
    }

    /**
     * Returns the first instant of the logical game day containing [instant].
     */
    fun startOfGameDay(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
    ): Instant {
        return startOfGameDay(gameDayOf(instant, rule), rule).toInstant()
    }

    /**
     * Returns the first instant of the next logical game day after [instant].
     */
    fun nextGameDayStart(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
    ): Instant {
        return startOfGameDay(gameDayOf(instant, rule).plusDays(1), rule).toInstant()
    }

    /**
     * Returns true when both instants are in the same logical game day.
     */
    fun isSameGameDay(
        first: Instant,
        second: Instant,
        rule: GameDayRule = GameDayRule(),
    ): Boolean {
        return gameDayOf(first, rule) == gameDayOf(second, rule)
    }

    /**
     * Returns the first instant of the logical game week containing [instant].
     *
     * Week boundaries are calculated from logical game days, not raw calendar midnights.
     */
    fun startOfGameWeek(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): Instant {
        val gameDay = gameDayOf(instant, rule)
        val daysSinceStart = Math.floorMod(gameDay.dayOfWeek.value - firstDayOfWeek.value, 7)
        return startOfGameDay(gameDay.minusDays(daysSinceStart.toLong()), rule).toInstant()
    }

    /**
     * Returns the first instant of the next logical game week after [instant].
     */
    fun nextGameWeekStart(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): Instant {
        val currentStart = startOfGameWeek(instant, rule, firstDayOfWeek).atZone(rule.zoneId).toLocalDate()
        return startOfGameDay(currentStart.plusWeeks(1), rule).toInstant()
    }

    /**
     * Returns the first instant of the logical game month containing [instant].
     *
     * Month boundaries are calculated from logical game days, not raw calendar midnights.
     */
    fun startOfGameMonth(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
    ): Instant {
        return startOfGameDay(gameDayOf(instant, rule).withDayOfMonth(1), rule).toInstant()
    }

    /**
     * Returns the first instant of the next logical game month after [instant].
     */
    fun nextGameMonthStart(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
    ): Instant {
        val monthStart = gameDayOf(instant, rule).withDayOfMonth(1)
        return startOfGameDay(monthStart.plusMonths(1), rule).toInstant()
    }

    /**
     * Returns the next occurrence of [time] in the rule's zone strictly after [instant].
     *
     * If [instant] is exactly at [time], the returned value is tomorrow's occurrence.
     */
    fun nextTimeOfDay(
        instant: Instant,
        time: LocalTime,
        rule: GameDayRule = GameDayRule(),
    ): Instant {
        val local = instant.atZone(rule.zoneId)
        val today = local.toLocalDate().atTime(time).atZone(rule.zoneId)
        val next = if (today.toInstant() > instant) today else today.plusDays(1)
        return next.toInstant()
    }

    /**
     * Returns the next occurrence of [dayOfWeek] at [time] in the rule's zone strictly after [instant].
     *
     * If [instant] is exactly at the requested weekday/time, the returned value is the next week's occurrence.
     */
    fun nextWeekdayTime(
        instant: Instant,
        dayOfWeek: DayOfWeek,
        rule: GameDayRule = GameDayRule(),
        time: LocalTime = rule.dayStart,
    ): Instant {
        val local = instant.atZone(rule.zoneId)
        val currentDate = local.toLocalDate()
        val daysUntil = Math.floorMod(dayOfWeek.value - currentDate.dayOfWeek.value, 7)
        val candidate = currentDate.plusDays(daysUntil.toLong()).atTime(time).atZone(rule.zoneId)
        return if (candidate.toInstant() > instant) {
            candidate.toInstant()
        } else {
            candidate.plusWeeks(1).toInstant()
        }
    }

    /**
     * Returns the local date-time for the beginning of [gameDay] under [rule].
     */
    fun startOfGameDay(
        gameDay: LocalDate,
        rule: GameDayRule = GameDayRule(),
    ): ZonedDateTime {
        return LocalDateTime.of(gameDay, rule.dayStart)
            .atZone(rule.zoneId)
            .truncatedTo(ChronoUnit.SECONDS)
    }
}
