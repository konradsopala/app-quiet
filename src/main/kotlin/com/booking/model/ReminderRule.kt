package com.booking.model

import java.time.Duration

/**
 * A declarative rule describing when, and over which channel, a reminder should
 * be generated for a booking.
 *
 * Reminders are expressed as an [offset] *before* the booking's start instant.
 * A 24-hour offset, for example, yields a reminder fired one day ahead of the
 * appointment. The [com.booking.service.ReminderScheduler] materialises these
 * rules into concrete [Notification]s.
 */
class ReminderRule(
    val name: String,
    val offset: Duration,
    val channel: NotificationChannel,
    val priority: NotificationPriority = NotificationPriority.NORMAL,
    val template: String = DEFAULT_TEMPLATE,
    val enabled: Boolean = true
) {
    init {
        require(name.isNotBlank()) { "Reminder rule name must not be blank." }
        require(!offset.isNegative) { "Reminder offset must not be negative." }
    }

    /**
     * Expand [template] against a small set of booking-derived [tokens],
     * replacing every `{key}` occurrence with its mapped value. Unknown tokens
     * are left untouched so authoring mistakes remain visible.
     */
    fun render(tokens: Map<String, String>): String {
        var result = template
        for ((key, value) in tokens) {
            result = result.replace("{$key}", value)
        }
        return result
    }

    /** A copy of this rule with [enabled] flipped to false. */
    fun disabled(): ReminderRule =
        ReminderRule(name, offset, channel, priority, template, enabled = false)

    fun describeOffset(): String {
        val hours = offset.toHours()
        val minutes = offset.toMinutesPart()
        return when {
            hours >= 24 && hours % 24 == 0L -> "${hours / 24}d before"
            hours > 0 && minutes == 0 -> "${hours}h before"
            hours > 0 -> "${hours}h ${minutes}m before"
            else -> "${offset.toMinutes()}m before"
        }
    }

    override fun toString(): String =
        "ReminderRule($name, ${describeOffset()}, ${channel.label}, $priority, " +
            "enabled=$enabled)"

    companion object {
        const val DEFAULT_TEMPLATE: String =
            "Reminder: '{description}' for {customer} on {date} at {time}."

        /** A sensible default set of reminders applied to new bookings. */
        fun defaults(): List<ReminderRule> = listOf(
            ReminderRule(
                name = "Day-before email",
                offset = Duration.ofHours(24),
                channel = NotificationChannel.EMAIL,
                priority = NotificationPriority.NORMAL
            ),
            ReminderRule(
                name = "Two-hour SMS",
                offset = Duration.ofHours(2),
                channel = NotificationChannel.SMS,
                priority = NotificationPriority.HIGH,
                template = "Heads up {customer}: '{description}' at {time} today."
            )
        )
    }
}
