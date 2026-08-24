package com.booking.model

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * A single window of time on [date] during which [staffId] is
 * available to be assigned to a booking.
 *
 * Shifts don't recur — a weekly schedule is modelled as one [Shift]
 * per date, which keeps the model simple at the cost of the caller
 * having to materialise them (see
 * [com.booking.service.StaffService.addWeeklyShifts]).
 */
class Shift(
    val staffId: String,
    val date: LocalDate,
    val startTime: LocalTime,
    val durationMinutes: Int,
    val id: String = "shift_" + UUID.randomUUID().toString().replace("-", "").take(16)
) {
    init {
        require(durationMinutes > 0) { "Shift duration must be positive." }
    }

    val endTime: LocalTime
        get() = startTime.plusMinutes(durationMinutes.toLong())

    /** True if [startTime]..[endTime] on [date] fully contains [otherStart]..[otherEnd]. */
    fun covers(otherDate: LocalDate, otherStart: LocalTime, otherEnd: LocalTime): Boolean {
        if (date != otherDate) return false
        return !otherStart.isBefore(startTime) && !otherEnd.isAfter(endTime)
    }

    /** True if this shift's window on [date] overlaps [other]'s. */
    fun overlaps(other: Shift): Boolean {
        if (date != other.date) return false
        return startTime < other.endTime && other.startTime < endTime
    }

    override fun toString(): String = "[$id] staff:$staffId | $date $startTime-$endTime"
}
