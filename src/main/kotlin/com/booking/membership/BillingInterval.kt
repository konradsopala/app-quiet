package com.booking.membership

import java.time.LocalDate

/**
 * How often a plan bills. [months] is the length of one billing period.
 *
 * Period arithmetic is anchor-aware: a membership that started on the 31st
 * keeps billing on the last day of shorter months and returns to the 31st
 * afterwards, rather than drifting to the 28th forever (which is what naive
 * repeated `plusMonths(1)` does).
 */
enum class BillingInterval(val months: Int, val label: String) {
    MONTHLY(1, "Monthly"),
    QUARTERLY(3, "Quarterly"),
    ANNUAL(12, "Annual");

    /**
     * The start of the period that follows one beginning on [periodStart],
     * given the membership's original [anchor] date.
     */
    fun nextPeriodStart(periodStart: LocalDate, anchor: LocalDate): LocalDate {
        val monthsSinceAnchor = monthsBetween(anchor, periodStart) + months
        return anchor.plusMonths(monthsSinceAnchor.toLong())
    }

    /** The start of the period that precedes one beginning on [periodStart]. */
    fun previousPeriodStart(periodStart: LocalDate, anchor: LocalDate): LocalDate {
        val monthsSinceAnchor = monthsBetween(anchor, periodStart) - months
        return anchor.plusMonths(monthsSinceAnchor.toLong())
    }

    /** Number of whole days in the period starting at [periodStart]. */
    fun lengthInDays(periodStart: LocalDate, anchor: LocalDate): Long =
        nextPeriodStart(periodStart, anchor).toEpochDay() - periodStart.toEpochDay()

    private fun monthsBetween(from: LocalDate, to: LocalDate): Int =
        (to.year - from.year) * 12 + (to.monthValue - from.monthValue)

    companion object {
        /** Case-insensitive lookup that also accepts the display label. */
        fun parse(text: String): BillingInterval {
            val key = text.trim().uppercase()
            return values().firstOrNull { it.name == key || it.label.uppercase() == key }
                ?: throw IllegalArgumentException("Unknown billing interval '$text' (use MONTHLY, QUARTERLY or ANNUAL)")
        }
    }
}
