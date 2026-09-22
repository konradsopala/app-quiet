package com.booking.model

import java.time.LocalDate

/**
 * Value types produced by the analytics layer.
 *
 * These used to be nested inside `AnalyticsEngine` and `StatisticsService`;
 * they now live in the model package so reports, the CLI and any future
 * exporter can reference them without depending on the engine itself.
 */

/** Number of confirmed bookings on a single [date]. */
data class DateCount(val date: LocalDate, val count: Int)

/**
 * One row of the customer leaderboard.
 *
 * Replaces the former `StatisticsService.CustomerCount` (name + count) and
 * `AnalyticsEngine.CustomerStat` (name + count + minutes + spend), which
 * carried overlapping data for the same customer.
 */
data class CustomerLeaderboardEntry(
    val name: String,
    val bookingCount: Int,
    val totalMinutes: Long,
    val totalSpend: Double
) {
    override fun toString(): String =
        "$name — $bookingCount booking(s), $totalMinutes min, " +
            "$%.2f".format(totalSpend)
}

/** Peak-day utilisation of a single bookable resource. */
data class ResourceUtilisation(
    val resourceId: String,
    val resourceName: String,
    val percent: Double
)

/** Booked share of a staff member's scheduled shift minutes. */
data class StaffUtilisation(
    val staffId: String,
    val staffName: String,
    val percent: Double
)

/** Booked minutes on [date] as a fraction of [capacityMinutes]. */
data class DayUtilisation(
    val date: LocalDate,
    val bookedMinutes: Long,
    val capacityMinutes: Int,
    val ratio: Double
) {
    /** A [width]-cell ASCII bar visualising [ratio]. */
    fun bar(width: Int = 20): String {
        val filled = (ratio * width).toInt().coerceIn(0, width)
        return "█".repeat(filled) + "░".repeat(width - filled)
    }

    override fun toString(): String =
        "$date ${bar()} %3.0f%% ($bookedMinutes/$capacityMinutes min)"
            .format(ratio * 100)
}
