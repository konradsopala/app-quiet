package com.booking.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * A discovered open window on a resource — the output unit of
 * [com.booking.service.AvailabilityService].
 *
 * A slot means: "on [date], resource [resourceName] can take a booking of
 * [durationMinutes] minutes starting at [startTime], and after that booking
 * there would still be [remainingCapacity] of [totalCapacity] concurrent
 * places free." A slot is only ever emitted when [remainingCapacity] >= 1, so
 * the value is always the free headroom *including* the hypothetical booking
 * the search was sizing for.
 *
 * The type is a pure value object: it carries no reference back to the live
 * booking set, so it can be sorted, filtered, rendered, and handed to the CLI
 * without risk of it going stale mid-computation. Callers that want to act on
 * a slot re-validate through the normal create path.
 */
data class AvailabilitySlot(
    val resourceId: String,
    val resourceName: String,
    val date: LocalDate,
    val startTime: LocalTime,
    val durationMinutes: Int,
    val remainingCapacity: Int,
    val totalCapacity: Int
) {
    init {
        require(durationMinutes > 0) { "durationMinutes must be positive." }
        require(totalCapacity >= 1) { "totalCapacity must be at least 1." }
        require(remainingCapacity in 1..totalCapacity) {
            "remainingCapacity ($remainingCapacity) must be in 1..$totalCapacity."
        }
    }

    /** Exclusive end of the window (start + duration). */
    val endTime: LocalTime
        get() = startTime.plusMinutes(durationMinutes.toLong())

    /**
     * True if this slot's window overlaps [other]'s on the same date and
     * resource. Used by [com.booking.service.AvailabilityService] callers that
     * want to de-duplicate a dense scan down to non-overlapping picks.
     */
    fun overlaps(other: AvailabilitySlot): Boolean {
        if (date != other.date || resourceId != other.resourceId) return false
        return startTime < other.endTime && other.startTime < endTime
    }

    override fun toString(): String {
        val cap = "$remainingCapacity/$totalCapacity free"
        return "$date $startTime-$endTime | $resourceName | $cap"
    }
}
