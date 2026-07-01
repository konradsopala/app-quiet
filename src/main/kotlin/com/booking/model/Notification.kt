package com.booking.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Channels through which a [Notification] may be delivered to a customer.
 *
 * Each channel carries a human-readable [label] used in console output and a
 * [maxLength] hint that the [com.booking.service.NotificationService] uses to
 * decide whether a message body must be truncated before dispatch.
 */
enum class NotificationChannel(val label: String, val maxLength: Int) {
    EMAIL("Email", 4_000),
    SMS("SMS", 160),
    PUSH("Push", 256),
    CONSOLE("Console", Int.MAX_VALUE);

    /** True if [body] fits within this channel's [maxLength] without truncation. */
    fun fits(body: String): Boolean = body.length <= maxLength

    companion object {
        /** Parse a channel from user input, accepting either name or label. */
        fun fromInput(raw: String): NotificationChannel? {
            val normalized = raw.trim().uppercase()
            return entries.firstOrNull {
                it.name == normalized || it.label.uppercase() == normalized
            }
        }
    }
}

/**
 * The delivery lifecycle of a [Notification].
 *
 * A notification starts [PENDING], moves to [SENT] once the service has handed
 * it to a channel, and ends in [FAILED] if dispatch threw. [CANCELLED] is used
 * when the owning booking is cancelled before the notification fires.
 */
enum class NotificationStatus {
    PENDING, SENT, FAILED, CANCELLED
}

/**
 * Priority buckets that influence dispatch ordering. Higher [weight] wins when
 * the scheduler flushes a batch of due notifications.
 */
enum class NotificationPriority(val weight: Int) {
    LOW(1), NORMAL(5), HIGH(10), URGENT(20)
}

/**
 * An immutable record describing a single message destined for a customer,
 * optionally tied to a booking via [bookingId].
 *
 * The body and subject are captured at construction time; the only mutable
 * facets are [status] and the [sentAt] timestamp, both managed by the
 * [com.booking.service.NotificationService].
 */
class Notification(
    val recipient: String,
    val channel: NotificationChannel,
    val subject: String,
    val body: String,
    val priority: NotificationPriority = NotificationPriority.NORMAL,
    val bookingId: String? = null,
    val scheduledFor: LocalDateTime = LocalDateTime.now()
) {
    val id: String = UUID.randomUUID().toString()

    var status: NotificationStatus = NotificationStatus.PENDING
        private set

    var sentAt: LocalDateTime? = null
        private set

    private var failureReason: String? = null

    /** Mark the notification as successfully dispatched at [at]. */
    fun markSent(at: LocalDateTime = LocalDateTime.now()) {
        check(status == NotificationStatus.PENDING) {
            "Only pending notifications can be sent (was $status)."
        }
        status = NotificationStatus.SENT
        sentAt = at
    }

    /** Mark the notification as failed, capturing [reason] for diagnostics. */
    fun markFailed(reason: String) {
        check(status == NotificationStatus.PENDING) {
            "Only pending notifications can fail (was $status)."
        }
        status = NotificationStatus.FAILED
        failureReason = reason
    }

    /** Cancel a not-yet-sent notification. No-op if already terminal. */
    fun cancel() {
        if (status == NotificationStatus.PENDING) {
            status = NotificationStatus.CANCELLED
        }
    }

    fun failureReasonOrNull(): String? = failureReason

    /** True if this notification is eligible to fire at [now]. */
    fun isDue(now: LocalDateTime): Boolean =
        status == NotificationStatus.PENDING && !scheduledFor.isAfter(now)

    /**
     * Render the effective body, truncating to the channel limit and appending
     * an ellipsis when the original body would overflow.
     */
    fun renderBody(): String {
        if (channel.fits(body)) return body
        val limit = (channel.maxLength - 1).coerceAtLeast(0)
        return body.take(limit) + "…"
    }

    override fun toString(): String {
        val whenSuffix = sentAt?.let { " | sent:$it" } ?: " | for:$scheduledFor"
        val bookingSuffix = bookingId?.let { " | booking:$it" } ?: ""
        return "[$id] ${channel.label} -> $recipient | $subject | " +
            "$priority | $status$whenSuffix$bookingSuffix"
    }
}
