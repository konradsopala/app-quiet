package com.booking.service

import com.booking.model.Booking
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Creates and manages recurring booking series.
 *
 * A series is identified by a generated [seriesId] stored on each [Booking];
 * occurrences are spaced by the chosen [Cadence] starting from the first date.
 * Each occurrence is independently validated through [BookingValidator] —
 * occurrences that fail (e.g. capacity, weekend block) are skipped and
 * reported, so the rest of the series still lands.
 */
class RecurringBookingService(
    private val service: BookingService,
    private val validator: BookingValidator
) {

    enum class Cadence {
        DAILY, WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, ANNUAL;

        fun next(date: LocalDate): LocalDate = when (this) {
            DAILY     -> date.plusDays(1)
            WEEKLY    -> date.plusWeeks(1)
            BIWEEKLY  -> date.plusWeeks(2)
            MONTHLY   -> date.plusMonths(1)
            QUARTERLY -> date.plusMonths(3)
            ANNUAL    -> date.plusYears(1)
        }
    }

    data class Skipped(val date: LocalDate, val reasons: List<String>)
    data class Result(val seriesId: String, val created: List<Booking>, val skipped: List<Skipped>)

    fun createSeries(
        customerName: String,
        firstDate: LocalDate,
        startTime: LocalTime,
        durationMinutes: Int,
        description: String,
        cadence: Cadence,
        count: Int
    ): Result {
        require(count >= 1) { "Series count must be at least 1." }

        val seriesId = UUID.randomUUID().toString()
        val created = mutableListOf<Booking>()
        val skipped = mutableListOf<Skipped>()

        var date = firstDate
        repeat(count) {
            val v = validator.validateNewBooking(customerName, date, startTime, durationMinutes, description)
            if (v.valid) {
                try {
                    created.add(
                        service.createBooking(customerName, date, startTime, durationMinutes, description, seriesId)
                    )
                } catch (e: IllegalArgumentException) {
                    skipped.add(Skipped(date, listOf(e.message ?: "Unknown error.")))
                }
            } else {
                skipped.add(Skipped(date, v.errors))
            }
            date = cadence.next(date)
        }
        return Result(seriesId, created, skipped)
    }

    /** Cancels every confirmed booking in [seriesId]. Returns the number cancelled. */
    fun cancelSeries(seriesId: String): Int {
        val ids = service.findBySeries(seriesId)
            .filter { it.status == Booking.Status.CONFIRMED }
            .map { it.id }
        var count = 0
        for (id in ids) {
            if (service.cancelBooking(id)) count++
        }
        if (count > 0) {
            service.auditLog.log(
                seriesId, AuditLog.Action.SERIES_CANCELLED,
                "Cancelled $count booking(s) in series"
            )
        }
        return count
    }
}
