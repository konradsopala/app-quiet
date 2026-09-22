package com.booking.service

import com.booking.model.CustomerLeaderboardEntry
import com.booking.model.DateCount
import com.booking.model.DayUtilisation
import com.booking.model.ResourceUtilisation
import com.booking.model.StaffUtilisation
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Read-only analytics over a [BookingService]'s current booking set.
 *
 * This is the single home for derived metrics. It absorbs what used to be
 * `StatisticsService` (busiest day, per-active-day averages, cancellation
 * rate, resource and staff utilisation) so the CLI's statistics screen and
 * the report generator share one implementation and one set of value types
 * (see `com.booking.model.Metrics.kt`).
 *
 * The engine never mutates bookings; every method snapshots the service's
 * view at call time via [BookingService.confirmedBookings].
 */
class AnalyticsEngine(private val service: BookingService) {

    // ── Volume ────────────────────────────────────────────────────────

    /** Total booked minutes across all confirmed bookings. */
    fun totalBookedMinutes(): Long =
        service.confirmedBookings().sumOf { it.durationMinutes.toLong() }

    /** Sum of all persisted quote totals, treating un-quoted bookings as zero. */
    fun totalRevenue(): Double =
        service.confirmedBookings().sumOf { it.quote?.total ?: 0.0 }

    /** Mean booking duration in minutes, or 0 when there are no bookings. */
    fun averageDurationMinutes(): Double {
        val list = service.confirmedBookings()
        if (list.isEmpty()) return 0.0
        return list.sumOf { it.durationMinutes.toLong() }.toDouble() / list.size
    }

    /**
     * Average confirmed bookings per **distinct** booking day (not per
     * calendar day in range), so a day with no bookings doesn't drag the
     * average down. Returns 0.0 when there are no bookings.
     */
    fun averageBookingsPerActiveDay(): Double {
        val confirmed = service.confirmedBookings()
        if (confirmed.isEmpty()) return 0.0
        val distinctDays = confirmed.map { it.date }.distinct().size
        return confirmed.size.toDouble() / distinctDays
    }

    /**
     * Percentage of all bookings (confirmed + cancelled) that ended up
     * cancelled. Returns 0.0 when there are no bookings at all.
     */
    fun cancellationRate(): Double {
        val total = service.listBookings().size
        if (total == 0) return 0.0
        return (service.cancelledBookings().size.toDouble() / total) * 100.0
    }

    // ── Timing ────────────────────────────────────────────────────────

    /** Confirmed bookings, grouped by date, sorted descending by count. */
    fun bookingsByDate(): List<DateCount> =
        service.confirmedBookings()
            .groupingBy { it.date }
            .eachCount()
            .map { (date, count) -> DateCount(date, count) }
            .sortedWith(compareByDescending<DateCount> { it.count }.thenBy { it.date })

    /** The date with the most confirmed bookings, or null if there are none. */
    fun busiestDate(): DateCount? = bookingsByDate().firstOrNull()

    /** Count of confirmed bookings grouped by day of week, Monday first. */
    fun bookingsByDayOfWeek(): Map<DayOfWeek, Int> {
        val counts = linkedMapOf<DayOfWeek, Int>()
        for (day in DayOfWeek.entries) counts[day] = 0
        for (booking in service.confirmedBookings()) {
            val day = booking.date.dayOfWeek
            counts[day] = (counts[day] ?: 0) + 1
        }
        return counts
    }

    /** Count of confirmed bookings grouped by start hour (0..23). */
    fun bookingsByHour(): Map<Int, Int> {
        val counts = sortedMapOf<Int, Int>()
        for (booking in service.confirmedBookings()) {
            val hour = booking.startTime.hour
            counts[hour] = (counts[hour] ?: 0) + 1
        }
        return counts
    }

    /** The hour-of-day with the most bookings, or null when there are none. */
    fun peakHour(): Int? = bookingsByHour().maxByOrNull { it.value }?.key

    /** Span between the earliest and latest confirmed booking date, in days. */
    fun bookingHorizonDays(): Long {
        val dates = service.confirmedBookings().map { it.date }
        if (dates.size < 2) return 0
        return ChronoUnit.DAYS.between(dates.min(), dates.max())
    }

    // ── Customers ─────────────────────────────────────────────────────

    /**
     * Top [limit] customers by number of confirmed bookings, ties broken by
     * total booked minutes then name for determinism.
     *
     * Customers with fewer than [minBookings] confirmed bookings are left
     * out entirely, which keeps one-off walk-ins off a "regulars" board.
     */
    fun topCustomers(limit: Int = 5, minBookings: Int = 1): List<CustomerLeaderboardEntry> {
        require(limit >= 1) { "limit must be at least 1." }
        require(minBookings >= 1) { "minBookings must be at least 1." }
        return service.confirmedBookings()
            .groupBy { it.customerName }
            .map { (name, bookings) ->
                CustomerLeaderboardEntry(
                    name = name,
                    bookingCount = bookings.size,
                    totalMinutes = bookings.sumOf { it.durationMinutes.toLong() },
                    totalSpend = bookings.sumOf { it.quote?.total ?: 0.0 }
                )
            }
            .filter { it.bookingCount >= minBookings }
            .sortedWith(
                compareByDescending<CustomerLeaderboardEntry> { it.bookingCount }
                    .thenByDescending { it.totalMinutes }
                    .thenBy { it.name }
            )
            .take(limit)
    }

    // ── Utilisation ───────────────────────────────────────────────────

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

    /**
     * Per-resource peak utilisation, sorted descending. For each
     * registered resource, walks every (resource, date) combination
     * and finds the day with the most confirmed bookings there. The
     * percentage is that peak divided by the resource's own capacity.
     *
     * Resources with no bookings on any day are still included with a
     * 0.0 percent — useful to spot unused capacity.
     */
    fun peakUtilisationByResource(): List<ResourceUtilisation> {
        val bookingsByResource = service.confirmedBookings()
            .filter { it.resourceId != null }
            .groupBy { it.resourceId!! }

        return service.resources.list().map { resource ->
            val resourceBookings = bookingsByResource[resource.id] ?: emptyList()
            val peakOnAnyDate = resourceBookings.groupingBy { it.date }.eachCount()
                .values.maxOrNull() ?: 0
            val percent = if (resource.capacity == 0) 0.0
                          else (peakOnAnyDate.toDouble() / resource.capacity) * 100.0
            ResourceUtilisation(resource.id, resource.name, percent)
        }.sortedByDescending { it.percent }
    }

    /**
     * Confirmed-booking share of each **active** staff member's scheduled
     * shift time, as a percentage. Deactivated staff are omitted: they can
     * no longer be assigned, so a 0% row for them is noise rather than
     * spare capacity. Staff with no shifts scheduled are reported at 0.0
     * rather than divided-by-zero, same convention as
     * [peakUtilisationByResource].
     */
    fun staffUtilisation(staff: StaffService): List<StaffUtilisation> {
        val confirmedByStaff = service.confirmedBookings()
            .filter { it.staffId != null }
            .groupBy { it.staffId!! }

        return staff.listActive().map { member ->
            val bookedMinutes = confirmedByStaff[member.id]?.sumOf { it.durationMinutes } ?: 0
            val shiftMinutes = staff.shiftsForStaff(member.id).sumOf { it.durationMinutes }
            val percent = if (shiftMinutes == 0) 0.0 else (bookedMinutes.toDouble() / shiftMinutes) * 100.0
            StaffUtilisation(member.id, member.name, percent)
        }.sortedByDescending { it.percent }
    }

    /**
     * Day-by-day utilisation between [from] and [to] inclusive, expressed as the
     * fraction of [minutesPerDay] that is booked. Values are clamped to 1.0 so a
     * heavily double-booked day reports 100%, not more.
     */
    fun dailyUtilisation(
        from: LocalDate,
        to: LocalDate,
        minutesPerDay: Int = DEFAULT_OPEN_MINUTES
    ): List<DayUtilisation> {
        require(!to.isBefore(from)) { "'to' must not precede 'from'." }
        require(minutesPerDay > 0) { "minutesPerDay must be positive." }

        val byDate = service.confirmedBookings().groupBy { it.date }
        val days = ChronoUnit.DAYS.between(from, to) + 1
        return (0 until days).map { offset ->
            val date = from.plusDays(offset)
            val booked = byDate[date].orEmpty().sumOf { it.durationMinutes.toLong() }
            val ratio = (booked.toDouble() / minutesPerDay).coerceAtMost(1.0)
            DayUtilisation(date, booked, minutesPerDay, ratio)
        }
    }

    // ── Digest ────────────────────────────────────────────────────────

    /** A compact textual digest suitable for a notification body or console. */
    fun digest(): String {
        val list = service.confirmedBookings()
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

    companion object {
        /** Default "open" window: 09:00–17:00 → 480 minutes. */
        const val DEFAULT_OPEN_MINUTES: Int = 480
        val DEFAULT_OPEN_FROM: LocalTime = LocalTime.of(9, 0)
        val DEFAULT_OPEN_TO: LocalTime = LocalTime.of(17, 0)
    }
}
