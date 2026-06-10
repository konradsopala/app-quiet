package com.booking.notification

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Mock SMS channel. Appends one line per message to a log file. The
 * summary is truncated to 160 chars to mirror SMS segmentation limits.
 *
 * Phone-number lookup is injected so callers can wire it up to a customer
 * directory later (today there is none, so the default is a deterministic
 * placeholder).
 */
class SmsNotifier(
    private val logPath: String = "sms.log",
    private val phoneFor: (String) -> String = defaultPhoneResolver,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    private val maxLength: Int = 160
) : Notifier {

    override val name: String = "sms"

    private val timestampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override fun handle(event: NotificationEvent) {
        val phone = phoneFor(event.customerName)
        val body = event.summary.let {
            if (it.length <= maxLength) it else it.take(maxLength - 1) + "…"
        }
        val line = "[${clock().format(timestampFmt)}] -> $phone (${event.customerName}): $body\n"
        File(logPath).appendText(line)
    }

    companion object {
        /**
         * Deterministic placeholder: derives a 10-digit number from the
         * customer name's hashCode so the same name always maps to the
         * same number across runs.
         */
        val defaultPhoneResolver: (String) -> String = { name ->
            val digits = "%010d".format(Math.floorMod(name.hashCode().toLong(), 10_000_000_000L))
            "+1${digits}"
        }
    }
}
