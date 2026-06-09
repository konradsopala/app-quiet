package com.booking.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * A pending booking request held until capacity frees up at the requested slot.
 *
 * Entries are ordered first by [priority] (higher first), then FIFO by
 * [addedAt] within the same priority. Promotion is the responsibility of
 * [com.booking.service.WaitlistService.tryPromoteAll].
 */
data class WaitlistEntry(
    val customerName: String,
    val date: LocalDate,
    val startTime: LocalTime,
    val durationMinutes: Int,
    val description: String,
    val priority: Priority = Priority.NORMAL,
    val id: String = UUID.randomUUID().toString(),
    val addedAt: LocalDateTime = LocalDateTime.now()
) {
    /**
     * Promotion ordering. Higher ordinal = higher priority. VIP entries
     * always cut in front of NORMAL ones regardless of arrival order.
     */
    enum class Priority {
        LOW, NORMAL, HIGH, VIP
    }

    val endTime: LocalTime
        get() = startTime.plusMinutes(durationMinutes.toLong())

    override fun toString(): String {
        val prioritySuffix = if (priority == Priority.NORMAL) "" else " | $priority"
        return "[wl:$id] $customerName | $date $startTime-$endTime | " +
            "$description (queued $addedAt)$prioritySuffix"
    }
}
