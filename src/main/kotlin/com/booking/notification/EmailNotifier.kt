package com.booking.notification

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Mock email channel. Instead of opening an SMTP connection, appends each
 * notification as an RFC 5322-ish message to an outbox file. Real SMTP can
 * be dropped in later by replacing this class.
 *
 * One file accumulates all messages, separated by a `--EOM--` line, so the
 * outbox can be tailed during local development.
 */
class EmailNotifier(
    private val outboxPath: String = "outbox.eml",
    private val fromAddress: String = "noreply@booking-system.local",
    private val toAddressFor: (String) -> String = defaultAddressResolver,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) : Notifier {

    override val name: String = "email"

    private val timestampFmt = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss")

    override fun handle(event: NotificationEvent) {
        val message = buildString {
            append("From: ").append(fromAddress).append('\n')
            append("To: ").append(toAddressFor(event.customerName)).append('\n')
            append("Date: ").append(clock().format(timestampFmt)).append('\n')
            append("Subject: ").append(subjectFor(event)).append('\n')
            append('\n')
            append(event.summary).append('\n')
            append("--EOM--\n")
        }
        File(outboxPath).appendText(message)
    }

    private fun subjectFor(event: NotificationEvent): String = when (event) {
        is NotificationEvent.BookingCreated    -> "Booking confirmed"
        is NotificationEvent.BookingCancelled  -> "Booking cancelled"
        is NotificationEvent.PaymentSucceeded  -> "Payment received"
        is NotificationEvent.PaymentFailed     -> "Payment failed"
        is NotificationEvent.PaymentRefunded   -> "Refund processed"
        is NotificationEvent.WaitlistPromoted  -> "Your waitlist slot opened"
    }

    companion object {
        /** Fallback resolver — slugifies the customer name. Replace via constructor. */
        val defaultAddressResolver: (String) -> String = { name ->
            val slug = name.lowercase()
                .replace(Regex("[^a-z0-9]+"), ".")
                .trim('.')
                .ifEmpty { "guest" }
            "$slug@example.invalid"
        }
    }
}
