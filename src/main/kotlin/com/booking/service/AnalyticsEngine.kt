package com.booking.service

import com.booking.model.Booking
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Read-only analytics over a [BookingService]'s current booking set.
 *
 * The engine never mutates bookings; it snapshots the service's view at call
 * time and computes aggregate metrics — utilisation, revenue, busiest windows,
 * customer leaderboards — used by the reporting menu and the notifications
 * digest.
 */
class AnalyticsEngine(private val service: BookingService) {

    private fun confirmed(): List<Booking> =
        service.listBookings().filter { it.status == Booking.Status.CONFIRMED }

    /** Total booked minutes across all confirmed bookings. */
    fun totalBookedMinutes(): Long =
        confirmed().sumOf { it.durationMinutes.toLong() }

    /** Sum of all persisted quote totals, treating un-quoted bookings as zero. */
    fun totalRevenue(): Double =
        confirmed().sumOf { it.quote?.total ?: 0.0 }

    /** Mean booking duration in minutes, or 0 when there are no bookings. */
    fun averageDurationMinutes(): Double {
        val list = confirmed()
        if (list.isEmpty()) return 0.0
        return list.sumOf { it.durationMinutes.toLong() }.toDouble() / list.size
    }

    /** Count of confirmed bookings grouped by day of week, Monday first. */
    fun bookingsByDayOfWeek(): Map<DayOfWeek, Int> {
        val counts = linkedMapOf<DayOfWeek, Int>()
        for (day in DayOfWeek.entries) counts[day] = 0
        for (booking in confirmed()) {
            val day = booking.date.dayOfWeek
            counts[day] = (counts[day] ?: 0) + 1
        }
        return counts
    }

    /** Count of confirmed bookings grouped by start hour (0..23). */
    fun bookingsByHour(): Map<Int, Int> {
        val counts = sortedMapOf<Int, Int>()
        for (booking in confirmed()) {
            val hour = booking.startTime.hour
            counts[hour] = (counts[hour] ?: 0) + 1
        }
        return counts
    }

    /** The hour-of-day with the most bookings, or null when there are none. */
    fun peakHour(): Int? =
        bookingsByHour().maxByOrNull { it.value }?.key

    /**
     * Top [limit] customers by number of confirmed bookings, ties broken by
     * total booked minutes then name for determinism.
     */
    fun topCustomers(limit: Int = 5): List<CustomerStat> {
        require(limit >= 1) { "limit must be at least 1." }
        val byCustomer = confirmed().groupBy { it.customerName }
        return byCustomer
            .map { (name, bookings) ->
                CustomerStat(
                    name = name,
                    bookingCount = bookings.size,
                    totalMinutes = bookings.sumOf { it.durationMinutes.toLong() },
                    totalSpend = bookings.sumOf { it.quote?.total ?: 0.0 }
                )
            }
            .sortedWith(
                compareByDescending<CustomerStat> { it.bookingCount }
                    .thenByDescending { it.totalMinutes }
                    .thenBy { it.name }
            )
            .take(limit)
    }

    /**
     * Day-by-day utilisation between [from] and [to] inclusive, expressed as the
     * fraction of [minutesPerDay] that is booked. Values are clamped to 1.0 so a
     * heavily double-booked day reports 100%, not more.
     */
    fun utilisation(
        from: LocalDate,
        to: LocalDate,
        minutesPerDay: Int = DEFAULT_OPEN_MINUTES
    ): List<DayUtilisation> {
        require(!to.isBefore(from)) { "'to' must not precede 'from'." }
        require(minutesPerDay > 0) { "minutesPerDay must be positive." }

        val byDate = confirmed().groupBy { it.date }
        val days = ChronoUnit.DAYS.between(from, to) + 1
        return (0 until days).map { offset ->
            val date = from.plusDays(offset)
            val booked = byDate[date].orEmpty().sumOf { it.durationMinutes.toLong() }
            val ratio = (booked.toDouble() / minutesPerDay).coerceAtMost(1.0)
            DayUtilisation(date, booked, minutesPerDay, ratio)
        }
    }

    /** A compact textual digest suitable for a notification body or console. */
    fun digest(): String {
        val list = confirmed()
        if (list.isEmpty()) return "No confirmed bookings yet."
        val peak = peakHour()?.let { "%02d:00".format(it) } ?: "n/a"
        val top = topCustomers(1).firstOrNull()?.name ?: "n/a"
        return buildString {
            appendLine("Bookings: ${list.size}")
            appendLine("Booked minutes: ${totalBookedMinutes()}")
            appendLine("Avg duration: %.0f min".format(averageDurationMinutes()))
            appendLine("Revenue: $%.2f".format(totalRevenue()))
            appendLine("Peak hour: $peak")
            append("Top customer: $top")
        }
    }

    data class CustomerStat(
        val name: String,
        val bookingCount: Int,
        val totalMinutes: Long,
        val totalSpend: Double
    ) {
        override fun toString(): String =
            "$name — $bookingCount booking(s), $totalMinutes min, " +
                "$%.2f".format(totalSpend)
    }

    data class DayUtilisation(
        val date: LocalDate,
        val bookedMinutes: Long,
        val capacityMinutes: Int,
        val ratio: Double
    ) {
        /** A 20-cell ASCII bar visualising [ratio]. */
        fun bar(width: Int = 20): String {
            val filled = (ratio * width).toInt().coerceIn(0, width)
            return "█".repeat(filled) + "░".repeat(width - filled)
        }

        override fun toString(): String =
            "$date ${bar()} %3.0f%% ($bookedMinutes/$capacityMinutes min)"
                .format(ratio * 100)
    }

    companion object {
        /** Default "open" window: 09:00–17:00 → 480 minutes. */
        const val DEFAULT_OPEN_MINUTES: Int = 480
        val DEFAULT_OPEN_FROM: LocalTime = LocalTime.of(9, 0)
        val DEFAULT_OPEN_TO: LocalTime = LocalTime.of(17, 0)
    }
}
