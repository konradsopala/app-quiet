package com.booking.service

import java.time.LocalDateTime
import java.util.Objects

/**
 * Immutable event log that records all booking mutations with timestamps and details.
 *
 * Supports querying entries by booking ID, action type, and producing aggregated summaries.
 */
class AuditLog {

    enum class Action {
        CREATED, CANCELLED, UPDATED, EXPORTED, QUOTED, WAITLISTED, PROMOTED, SERIES_CANCELLED,
        PAYMENT_INTENT_CREATED, PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_REFUNDED,
        ICAL_EXPORTED, RECEIPT_EXPORTED,
        COUPON_REGISTERED, COUPON_REDEEMED,
        RESOURCE_REGISTERED, RESOURCE_DELETED,
        SNAPSHOT_SAVED, SNAPSHOT_LOADED
    }

    data class Entry(
        val timestamp: LocalDateTime,
        val bookingId: String,
        val action: Action,
        val detail: String
    ) {
        override fun toString(): String =
            "[$timestamp] $bookingId — $action: $detail"
    }

    private val entries = mutableListOf<Entry>()

    // ── Record an event ──────────────────────────────────────────

    fun log(bookingId: String, action: Action, detail: String) {
        entries.add(Entry(LocalDateTime.now(), bookingId, action, detail))
    }

    /**
     * Replace the log with [newEntries]. Used by the snapshot restore
     * path to bring the in-memory log back to its persisted state. The
     * caller is responsible for ordering — entries are kept exactly as
     * supplied so downstream queries see the historical timestamps.
     */
    internal fun replaceAll(newEntries: List<Entry>) {
        entries.clear()
        entries.addAll(newEntries)
    }

    /** Append a pre-built entry. Used by restore paths that want to keep an explicit timestamp. */
    internal fun append(entry: Entry) {
        entries.add(entry)
    }

    // ── Query: all entries ───────────────────────────────────────

    fun getAll(): List<Entry> = entries.toList()

    // ── Query: entries for a specific booking ────────────────────

    fun getByBookingId(bookingId: String): List<Entry> {
        Objects.requireNonNull(bookingId, "bookingId must not be null")
        return entries.filter { it.bookingId == bookingId }
    }

    // ── Query: entries for a specific action type ────────────────

    fun getByAction(action: Action): List<Entry> =
        entries.filter { it.action == action }

    // ── Summary count by action type ─────────────────────────────

    fun summary(): String {
        val prefix = "Audit summary: "
        val sb = StringBuilder(prefix)
        val prefixLen = sb.length
        val counts = entries.groupingBy { it.action }.eachCount()
        counts.forEach { (action, count) ->
            sb.append("$count $action, ")
        }
        return if (sb.length > prefixLen) sb.substring(0, sb.length - 2) else "Audit summary: (empty)"
    }
}
