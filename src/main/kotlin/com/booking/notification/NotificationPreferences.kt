package com.booking.notification

/**
 * Per-customer notification routing rules.
 *
 * Two layers of suppression apply at dispatch time:
 *
 * 1. **Channel mute** — a customer can opt a whole channel out (`channelOptOuts`).
 *    If they've muted `sms`, no SMS messages reach them regardless of event type.
 *
 * 2. **Per-event mute** — a customer can opt out of a specific event type on
 *    a specific channel (`eventOptOuts`). e.g. "send me email confirmations
 *    but not email reminders for waitlist promotion."
 *
 * Lookups are case-insensitive on customer name. Customers with no rule
 * default to "receive everything on every enabled channel".
 */
class NotificationPreferences {

    /** Lightweight key for the event-type opt-out map. */
    enum class EventType {
        BOOKING_CREATED, BOOKING_CANCELLED,
        PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_REFUNDED,
        WAITLIST_PROMOTED;

        companion object {
            fun of(event: NotificationEvent): EventType = when (event) {
                is NotificationEvent.BookingCreated   -> BOOKING_CREATED
                is NotificationEvent.BookingCancelled -> BOOKING_CANCELLED
                is NotificationEvent.PaymentSucceeded -> PAYMENT_SUCCEEDED
                is NotificationEvent.PaymentFailed    -> PAYMENT_FAILED
                is NotificationEvent.PaymentRefunded  -> PAYMENT_REFUNDED
                is NotificationEvent.WaitlistPromoted -> WAITLIST_PROMOTED
            }
        }
    }

    private data class Rule(
        val channelOptOuts: MutableSet<String> = mutableSetOf(),
        val eventOptOuts: MutableSet<Pair<String, EventType>> = mutableSetOf()
    )

    private val rules = mutableMapOf<String, Rule>()

    private fun key(customerName: String) = customerName.lowercase().trim()
    private fun ruleFor(customerName: String) = rules.getOrPut(key(customerName)) { Rule() }

    // ── Mutators ───────────────────────────────────────────────────

    fun muteChannel(customerName: String, channel: String) {
        ruleFor(customerName).channelOptOuts.add(channel)
    }

    fun unmuteChannel(customerName: String, channel: String) {
        rules[key(customerName)]?.channelOptOuts?.remove(channel)
    }

    fun muteEvent(customerName: String, channel: String, eventType: EventType) {
        ruleFor(customerName).eventOptOuts.add(channel to eventType)
    }

    fun unmuteEvent(customerName: String, channel: String, eventType: EventType) {
        rules[key(customerName)]?.eventOptOuts?.remove(channel to eventType)
    }

    fun clear(customerName: String) {
        rules.remove(key(customerName))
    }

    // ── Queries ────────────────────────────────────────────────────

    /** Does the customer's rule allow [channel] to receive [event]? */
    fun allows(customerName: String, channel: String, event: NotificationEvent): Boolean {
        val rule = rules[key(customerName)] ?: return true
        if (channel in rule.channelOptOuts) return false
        return (channel to EventType.of(event)) !in rule.eventOptOuts
    }

    fun mutedChannels(customerName: String): Set<String> =
        rules[key(customerName)]?.channelOptOuts?.toSet() ?: emptySet()

    fun mutedEvents(customerName: String): Set<Pair<String, EventType>> =
        rules[key(customerName)]?.eventOptOuts?.toSet() ?: emptySet()

    /** All customers with any rule on file, lower-cased. */
    fun knownCustomers(): Set<String> = rules.keys.toSet()
}
