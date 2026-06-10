package com.booking.notification

import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Writes notifications to a [PrintStream] (stdout by default).
 *
 * Prefixed with a `[NOTIFY hh:mm:ss]` tag so notification output is easy
 * to distinguish from the regular CLI prompts.
 */
class ConsoleNotifier(
    private val out: PrintStream = System.out,
    private val clock: () -> LocalTime = LocalTime::now
) : Notifier {

    override val name: String = "console"

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    override fun handle(event: NotificationEvent) {
        out.println("[NOTIFY ${clock().format(timeFmt)}] (${event.customerName}) ${event.summary}")
    }
}
