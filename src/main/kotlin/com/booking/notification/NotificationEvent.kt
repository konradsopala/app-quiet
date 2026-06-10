package com.booking.notification

import com.booking.model.Booking
import com.booking.model.PaymentIntent
import com.booking.model.WaitlistEntry

/**
 * Sealed hierarchy of things the booking system can tell observers about.
 *
 * Each event carries enough context to render a message without the
 * notifier having to consult other services. [customerName] is exposed on
 * every event so downstream routing/templating can key off it uniformly.
 */
sealed class NotificationEvent {
    abstract val customerName: String
    abstract val summary: String

    data class BookingCreated(val booking: Booking) : NotificationEvent() {
        override val customerName: String get() = booking.customerName
        override val summary: String get() =
            "Booking ${booking.id.take(8)} confirmed for ${booking.date} ${booking.startTime}-${booking.endTime}"
    }

    data class BookingCancelled(val booking: Booking) : NotificationEvent() {
        override val customerName: String get() = booking.customerName
        override val summary: String get() =
            "Booking ${booking.id.take(8)} cancelled (${booking.date} ${booking.startTime})"
    }

    data class PaymentSucceeded(val intent: PaymentIntent, val booking: Booking) : NotificationEvent() {
        override val customerName: String get() = booking.customerName
        override val summary: String get() =
            "Payment of $%.2f %s confirmed for booking ${booking.id.take(8)}"
                .format(intent.amount, intent.currency)
    }

    data class PaymentFailed(val intent: PaymentIntent, val booking: Booking) : NotificationEvent() {
        override val customerName: String get() = booking.customerName
        override val summary: String get() =
            "Payment of $%.2f %s failed: ${intent.failureReason ?: "unknown"}"
                .format(intent.amount, intent.currency)
    }

    data class PaymentRefunded(val intent: PaymentIntent, val booking: Booking) : NotificationEvent() {
        override val customerName: String get() = booking.customerName
        override val summary: String get() =
            "Refund of $%.2f %s processed for booking ${booking.id.take(8)}"
                .format(intent.amount, intent.currency)
    }

    data class WaitlistPromoted(val entry: WaitlistEntry, val booking: Booking) : NotificationEvent() {
        override val customerName: String get() = booking.customerName
        override val summary: String get() =
            "Waitlist slot opened: booking ${booking.id.take(8)} for ${booking.date} ${booking.startTime} is now confirmed"
    }
}
