package com.booking.notification

/**
 * Fans out [NotificationEvent]s to every registered [Notifier].
 *
 * Notifier exceptions are isolated: a failure in one channel is logged to
 * stderr but never aborts dispatch to the rest. Notifiers are invoked in
 * registration order; downstream consumers must not rely on ordering for
 * correctness.
 *
 * Each channel can be **disabled** without unregistering — useful when an
 * operator wants to mute SMS during testing without losing the wiring.
 * Disabled channels are silently skipped at dispatch time.
 *
 * If [preferences] is provided, dispatch also consults the customer's
 * per-channel and per-event opt-outs before invoking a notifier.
 */
class NotificationDispatcher(
    private val preferences: NotificationPreferences = NotificationPreferences()
) {

    val prefs: NotificationPreferences get() = preferences

    private data class Channel(val notifier: Notifier, var enabled: Boolean = true)

    private val channels = mutableListOf<Channel>()

    fun register(notifier: Notifier, enabled: Boolean = true) {
        channels.add(Channel(notifier, enabled))
    }

    fun unregister(name: String): Boolean =
        channels.removeIf { it.notifier.name == name }

    fun registered(): List<String> = channels.map { it.notifier.name }

    /** All channel names with their current enabled state, in registration order. */
    fun channelStates(): List<Pair<String, Boolean>> =
        channels.map { it.notifier.name to it.enabled }

    /**
     * Toggle a channel on/off by name. Returns true if a channel matched.
     * No-op (returns false) if the channel isn't registered.
     */
    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val ch = channels.firstOrNull { it.notifier.name == name } ?: return false
        ch.enabled = enabled
        return true
    }

    fun isEnabled(name: String): Boolean =
        channels.firstOrNull { it.notifier.name == name }?.enabled ?: false

    fun dispatch(event: NotificationEvent) {
        for (ch in channels) {
            if (!ch.enabled) continue
            if (!preferences.allows(event.customerName, ch.notifier.name, event)) continue
            try {
                ch.notifier.handle(event)
            } catch (e: Exception) {
                System.err.println("[notify] '${ch.notifier.name}' failed: ${e.message}")
            }
        }
    }
}
