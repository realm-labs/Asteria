package io.github.realmlabs.asteria.game.time

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GameTimeTest {
    private val rule = GameDayRule(
        zoneId = ZoneId.of("Asia/Shanghai"),
        dayStart = LocalTime.of(5, 0),
    )

    @Test
    fun gameDayUsesConfiguredDayStart() {
        val beforeReset = Instant.parse("2026-05-01T20:59:59Z")
        val afterReset = Instant.parse("2026-05-01T21:00:00Z")

        assertEquals(LocalDate.of(2026, 5, 1), GameTime.gameDayOf(beforeReset, rule))
        assertEquals(LocalDate.of(2026, 5, 2), GameTime.gameDayOf(afterReset, rule))
        assertEquals(afterReset, GameTime.startOfGameDay(afterReset, rule))
    }

    @Test
    fun nextGameDayStartUsesLocalCalendarDay() {
        val instant = Instant.parse("2026-05-01T12:00:00Z")

        assertEquals(
            Instant.parse("2026-05-01T21:00:00Z"),
            GameTime.nextGameDayStart(instant, rule),
        )
    }

    @Test
    fun sameGameDayUsesLogicalDay() {
        val first = Instant.parse("2026-05-01T21:00:00Z")
        val second = Instant.parse("2026-05-02T20:59:59Z")
        val next = Instant.parse("2026-05-02T21:00:00Z")

        assertTrue(GameTime.isSameGameDay(first, second, rule))
        assertFalse(GameTime.isSameGameDay(first, next, rule))
    }

    @Test
    fun weeklyAndMonthlyStartsUseGameDayStart() {
        val instant = Instant.parse("2026-05-06T10:00:00Z")

        assertEquals(
            Instant.parse("2026-05-03T21:00:00Z"),
            ResetCycle.Weekly(DayOfWeek.MONDAY).currentStart(instant, rule),
        )
        assertEquals(
            Instant.parse("2026-04-30T21:00:00Z"),
            ResetCycle.Monthly.currentStart(instant, rule),
        )
    }

    @Test
    fun nextWeekdayTimeReturnsStrictFutureOccurrence() {
        val instant = Instant.parse("2026-05-04T02:00:00Z")

        assertEquals(
            Instant.parse("2026-05-11T02:00:00Z"),
            GameTime.nextWeekdayTime(
                instant = instant,
                dayOfWeek = DayOfWeek.MONDAY,
                rule = rule,
                time = LocalTime.of(10, 0),
            ),
        )
    }

    @Test
    fun cooldownCalculatesReadyTimeAndRemainingTime() {
        val lastUsedAt = Instant.parse("2026-05-01T00:00:00Z")

        assertFalse(Cooldown.isReady(lastUsedAt, Instant.parse("2026-05-01T00:04:59Z"), 5.minutes))
        assertTrue(Cooldown.isReady(lastUsedAt, Instant.parse("2026-05-01T00:05:00Z"), 5.minutes))
        assertEquals(
            1.seconds,
            Cooldown.remaining(lastUsedAt, Instant.parse("2026-05-01T00:04:59Z"), 5.minutes),
        )
    }

    @Test
    fun gameTimeRangeUsesHalfOpenBoundaries() {
        val range = GameTimeRange(
            start = Instant.parse("2026-05-01T00:00:00Z"),
            end = Instant.parse("2026-05-02T00:00:00Z"),
        )

        assertTrue(range.contains(Instant.parse("2026-05-01T00:00:00Z")))
        assertFalse(range.contains(Instant.parse("2026-05-02T00:00:00Z")))
        assertTrue(
            range.overlaps(
                GameTimeRange(
                    start = Instant.parse("2026-05-01T23:59:59Z"),
                    end = Instant.parse("2026-05-02T01:00:00Z"),
                ),
            ),
        )
    }

    @Test
    fun dailyTimeWindowCanCrossMidnight() {
        val window = DailyTimeWindow(
            start = LocalTime.of(22, 0),
            end = LocalTime.of(2, 0),
        )

        assertTrue(window.contains(Instant.parse("2026-05-01T15:00:00Z"), rule))
        assertTrue(window.contains(Instant.parse("2026-05-01T17:00:00Z"), rule))
        assertFalse(window.contains(Instant.parse("2026-05-01T18:00:00Z"), rule))
    }

    @Test
    fun weeklyTimeWindowCanCrossWeekBoundary() {
        val window = WeeklyTimeWindow(
            startDay = DayOfWeek.FRIDAY,
            startTime = LocalTime.of(20, 0),
            endDay = DayOfWeek.MONDAY,
            endTime = LocalTime.of(5, 0),
        )

        assertTrue(window.contains(Instant.parse("2026-05-01T13:00:00Z"), rule))
        assertTrue(window.contains(Instant.parse("2026-05-03T20:59:59Z"), rule))
        assertFalse(window.contains(Instant.parse("2026-05-03T21:00:00Z"), rule))
    }

    @Test
    fun currentGameDayRangeUsesConfiguredDayStart() {
        val range = currentGameDayRange(Instant.parse("2026-05-01T20:00:00Z"), rule)

        assertEquals(Instant.parse("2026-04-30T21:00:00Z"), range.start)
        assertEquals(Instant.parse("2026-05-01T21:00:00Z"), range.end)
    }
}
