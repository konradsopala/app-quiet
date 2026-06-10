package com.booking.notification

/**
 * Receives [NotificationEvent]s emitted by the booking system.
 *
 * Implementations should be side-effect-tolerant: they may print, write to
 * a file, or call out to a network. The dispatcher catches per-notifier
 * exceptions so one failing channel never breaks the others.
 */
interface Notifier {
    /** Short identifier used for logging and channel routing (e.g. "console", "email"). */
    val name: String

    /** Handle one event. Implementations should be idempotent where possible. */
    fun handle(event: NotificationEvent)
}
