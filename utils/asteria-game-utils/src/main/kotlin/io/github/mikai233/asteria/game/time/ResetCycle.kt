package io.github.mikai233.asteria.game.time

import java.time.DayOfWeek
import java.time.Instant

/**
 * A repeatable reset schedule used by activities, shops, quests, and cooldown pools.
 */
sealed interface ResetCycle {
    /**
     * Returns the start instant of the cycle containing [instant].
     */
    fun currentStart(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
    ): Instant

    /**
     * Returns the next cycle start strictly after [instant].
     */
    fun nextStart(
        instant: Instant,
        rule: GameDayRule = GameDayRule(),
    ): Instant

    /**
     * Daily reset using [GameDayRule.dayStart].
     */
    data object Daily : ResetCycle {
        override fun currentStart(instant: Instant, rule: GameDayRule): Instant {
            return GameTime.startOfGameDay(instant, rule)
        }

        override fun nextStart(instant: Instant, rule: GameDayRule): Instant {
            return GameTime.nextGameDayStart(instant, rule)
        }
    }

    /**
     * Weekly reset using [firstDayOfWeek] and [GameDayRule.dayStart].
     */
    data class Weekly(
        /**
         * The first logical game day of a week.
         */
        val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ) : ResetCycle {
        override fun currentStart(instant: Instant, rule: GameDayRule): Instant {
            return GameTime.startOfGameWeek(instant, rule, firstDayOfWeek)
        }

        override fun nextStart(instant: Instant, rule: GameDayRule): Instant {
            return GameTime.nextGameWeekStart(instant, rule, firstDayOfWeek)
        }
    }

    /**
     * Monthly reset at the first logical game day of the month.
     */
    data object Monthly : ResetCycle {
        override fun currentStart(instant: Instant, rule: GameDayRule): Instant {
            return GameTime.startOfGameMonth(instant, rule)
        }

        override fun nextStart(instant: Instant, rule: GameDayRule): Instant {
            return GameTime.nextGameMonthStart(instant, rule)
        }
    }
}
