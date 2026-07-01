package com.booking.service

import com.booking.model.Notification
import com.booking.model.NotificationChannel
import com.booking.model.NotificationPriority
import com.booking.model.NotificationStatus
import java.time.LocalDateTime

/**
 * In-memory dispatcher and registry for [Notification]s.
 *
 * The service keeps every notification it has ever seen (queued or terminal) so
 * callers can audit delivery history. Dispatch is synchronous: [flushDue] walks
 * the pending queue, ordered by [com.booking.model.NotificationPriority], and
 * hands each due notification to a [Channel] sink.
 *
 * A [Channel] sink is just a function from rendered text to nothing; the default
 * sinks print to the console, but tests (and the analytics layer) can swap in a
 * collector to capture output.
 */
class NotificationService {

    /** A delivery sink for a single channel. */
    fun interface Channel {
        fun deliver(notification: Notification, renderedBody: String)
    }

    private val queue = mutableListOf<Notification>()
    private val sinks = mutableMapOf<NotificationChannel, Channel>()
    private var sentCount = 0
    private var failedCount = 0

    init {
        // Default every channel to console delivery so the service is usable
        // out of the box without explicit wiring.
        for (channel in NotificationChannel.entries) {
            sinks[channel] = Channel { notification, body ->
                println("  → [${channel.label}] ${notification.recipient}: $body")
            }
        }
    }

    /** Override the delivery sink for [channel]. */
    fun registerSink(channel: NotificationChannel, sink: Channel) {
        sinks[channel] = sink
    }

    /** Enqueue [notification] for later dispatch. Returns it for chaining. */
    fun enqueue(notification: Notification): Notification {
        queue.add(notification)
        return notification
    }

    /** Convenience overload that builds and enqueues a notification. */
    fun enqueue(
        recipient: String,
        channel: NotificationChannel,
        subject: String,
        body: String,
        priority: NotificationPriority = NotificationPriority.NORMAL,
        bookingId: String? = null,
        scheduledFor: LocalDateTime = LocalDateTime.now()
    ): Notification = enqueue(
        Notification(recipient, channel, subject, body, priority, bookingId, scheduledFor)
    )

    /**
     * Dispatch every pending notification whose scheduled time is at or before
     * [now], in descending priority order. Returns the number successfully sent.
     */
    fun flushDue(now: LocalDateTime = LocalDateTime.now()): Int {
        val due = queue
            .filter { it.isDue(now) }
            .sortedWith(
                compareByDescending<Notification> { it.priority.weight }
                    .thenBy { it.scheduledFor }
            )

        var sent = 0
        for (notification in due) {
            val sink = sinks[notification.channel]
            if (sink == null) {
                notification.markFailed("No sink registered for ${notification.channel}.")
                failedCount++
                continue
            }
            try {
                sink.deliver(notification, notification.renderBody())
                notification.markSent(now)
                sentCount++
                sent++
            } catch (ex: Exception) {
                notification.markFailed(ex.message ?: ex.javaClass.simpleName)
                failedCount++
            }
        }
        return sent
    }

    /** Cancel all pending notifications attached to [bookingId]. */
    fun cancelForBooking(bookingId: String): Int {
        var cancelled = 0
        for (notification in queue) {
            if (notification.bookingId == bookingId &&
                notification.status == NotificationStatus.PENDING
            ) {
                notification.cancel()
                cancelled++
            }
        }
        return cancelled
    }

    fun pending(): List<Notification> =
        queue.filter { it.status == NotificationStatus.PENDING }

    fun history(): List<Notification> = queue.toList()

    fun historyFor(bookingId: String): List<Notification> =
        queue.filter { it.bookingId == bookingId }

    /** Summary counters for the analytics layer and CLI status lines. */
    fun stats(): NotificationStats = NotificationStats(
        total = queue.size,
        pending = queue.count { it.status == NotificationStatus.PENDING },
        sent = sentCount,
        failed = failedCount,
        cancelled = queue.count { it.status == NotificationStatus.CANCELLED }
    )

    data class NotificationStats(
        val total: Int,
        val pending: Int,
        val sent: Int,
        val failed: Int,
        val cancelled: Int
    ) {
        fun deliveryRate(): Double =
            if (sent + failed == 0) 0.0 else sent.toDouble() / (sent + failed)

        override fun toString(): String =
            "Notifications: total=$total pending=$pending sent=$sent " +
                "failed=$failed cancelled=$cancelled " +
                "(delivery=%.0f%%)".format(deliveryRate() * 100)
    }
}
