package com.booking.service

import com.booking.model.Booking
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Derived metrics over a [BookingService].
 *
 * Kept separate from [BookingService.getStatistics] so the core service
 * stays small and the heavier analytical queries (busiest day, customer
 * leaderboards, capacity utilisation) live with the reporting concerns
 * instead of the CRUD layer.
 *
 * All calculations operate on the snapshot returned by
 * [BookingService.listBookings] at call time; the service is treated as
 * a read-only source.
 */
class StatisticsService(private val service: BookingService) {

    data class DateCount(val date: LocalDate, val count: Int)
    data class CustomerCount(val customer: String, val count: Int)

    /** Confirmed bookings, grouped by date, sorted descending by count. */
    fun bookingsByDate(): List<DateCount> =
        confirmed().groupingBy { it.date }.eachCount()
            .map { (d, c) -> DateCount(d, c) }
            .sortedByDescending { it.count }

    /** The date with the most confirmed bookings, or null if there are none. */
    fun busiestDate(): DateCount? = bookingsByDate().firstOrNull()

    /**
     * Average confirmed bookings per **distinct** booking day (not per
     * calendar day in range), so a day with no bookings doesn't drag the
     * average down. Returns 0.0 when there are no bookings.
     */
    fun averageBookingsPerActiveDay(): Double {
        val confirmed = confirmed()
        if (confirmed.isEmpty()) return 0.0
        val distinctDays = confirmed.map { it.date }.distinct().size
        return confirmed.size.toDouble() / distinctDays
    }

    /**
     * Top N customers ordered by confirmed-booking count. Ties are
     * broken by alphabetical name to keep results stable across calls.
     */
    fun topCustomers(limit: Int = 5): List<CustomerCount> {
        require(limit >= 1) { "limit must be >= 1" }
        return confirmed().groupingBy { it.customerName }.eachCount()
            .map { (name, count) -> CustomerCount(name, count) }
            .sortedWith(compareByDescending<CustomerCount> { it.count }.thenBy { it.customer })
            .take(limit)
    }

    /**
     * Fraction of capacity used on the busiest day, expressed as a
     * percentage. Returns 0.0 when there are no bookings (so callers can
     * format it as "0%" rather than NaN).
     */
    fun peakCapacityUtilisation(): Double {
        val busiest = busiestDate() ?: return 0.0
        val capacity = service.capacity.coerceAtLeast(1)
        return (busiest.count.toDouble() / capacity) * 100.0
    }

    /** Span between the earliest and latest booking date, in days. */
    fun bookingHorizonDays(): Long {
        val dates = confirmed().map { it.date }
        if (dates.size < 2) return 0
        val earliest = dates.min()
        val latest = dates.max()
        return ChronoUnit.DAYS.between(earliest, latest)
    }

    private fun confirmed(): List<Booking> =
        service.listBookings().filter { it.status == Booking.Status.CONFIRMED }
}
