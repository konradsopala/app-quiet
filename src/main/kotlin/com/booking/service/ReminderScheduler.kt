package com.booking.service

import com.booking.model.Booking
import com.booking.model.Notification
import com.booking.model.ReminderRule
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Materialises [ReminderRule]s into concrete [Notification]s for bookings and
 * registers them with a [NotificationService].
 *
 * The scheduler owns a mutable list of active rules. When a booking is created
 * (or rescheduled) the caller invokes [scheduleFor], which computes each rule's
 * fire time relative to the booking's start instant and enqueues a notification.
 *
 * Rules whose fire time has already passed are skipped — there is no point
 * scheduling a reminder into the past — and a count of those is returned so the
 * UI can surface "2 reminders skipped (too late)".
 */
class ReminderScheduler(
    private val notifications: NotificationService,
    rules: List<ReminderRule> = ReminderRule.defaults()
) {

    private val rules = rules.toMutableList()

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun rules(): List<ReminderRule> = rules.toList()

    fun addRule(rule: ReminderRule) {
        rules.add(rule)
    }

    /** Remove rules by [name]; returns the number removed. */
    fun removeRule(name: String): Int {
        val before = rules.size
        rules.removeAll { it.name.equals(name, ignoreCase = true) }
        return before - rules.size
    }

    /**
     * The result of scheduling reminders for a single booking: how many fired
     * into the queue and how many were skipped because their offset placed them
     * in the past.
     */
    data class ScheduleResult(val scheduled: Int, val skippedPast: Int) {
        val attempted: Int get() = scheduled + skippedPast
        override fun toString(): String =
            "Scheduled $scheduled reminder(s)" +
                if (skippedPast > 0) ", skipped $skippedPast (too late)" else ""
    }

    /**
     * Generate reminders for [booking] using every enabled rule, anchored at
     * [now] for the past-check. The recipient defaults to the customer name; a
     * real system would resolve a contact address, but the demo uses the name.
     */
    fun scheduleFor(
        booking: Booking,
        recipient: String = booking.customerName,
        now: LocalDateTime = LocalDateTime.now()
    ): ScheduleResult {
        val start = LocalDateTime.of(booking.date, booking.startTime)
        var scheduled = 0
        var skipped = 0

        for (rule in rules) {
            if (!rule.enabled) continue
            val fireAt = start.minus(rule.offset)
            if (fireAt.isBefore(now)) {
                skipped++
                continue
            }
            val body = rule.render(tokensFor(booking))
            notifications.enqueue(
                Notification(
                    recipient = recipient,
                    channel = rule.channel,
                    subject = "Reminder: ${booking.description}",
                    body = body,
                    priority = rule.priority,
                    bookingId = booking.id,
                    scheduledFor = fireAt
                )
            )
            scheduled++
        }
        return ScheduleResult(scheduled, skipped)
    }

    /** Cancel any pending reminders previously scheduled for [booking]. */
    fun cancelFor(booking: Booking): Int =
        notifications.cancelForBooking(booking.id)

    /**
     * Reschedule reminders for a booking whose time changed: cancel the old
     * pending ones, then schedule fresh from the current rule set.
     */
    fun reschedule(
        booking: Booking,
        now: LocalDateTime = LocalDateTime.now()
    ): ScheduleResult {
        cancelFor(booking)
        return scheduleFor(booking, now = now)
    }

    private fun tokensFor(booking: Booking): Map<String, String> = mapOf(
        "customer" to booking.customerName,
        "description" to booking.description,
        "date" to booking.date.format(dateFormat),
        "time" to booking.startTime.format(timeFormat),
        "id" to booking.id
    )
}
