package com.booking.service

import com.booking.model.Booking
import com.booking.model.WaitlistEntry
import java.time.LocalDate
import java.time.LocalTime

/**
 * FIFO waitlist for booking requests that hit capacity.
 *
 * The CLI offers a waitlist add when [BookingValidator] rejects a new booking
 * solely on capacity. After every successful cancellation [tryPromoteAll]
 * walks the queue and promotes any entry whose slot now passes full
 * validation, freeing slots in order.
 */
class WaitlistService(
    private val service: BookingService,
    private val validator: BookingValidator
) {

    private val entries = mutableListOf<WaitlistEntry>()

    // ── Add ────────────────────────────────────────────────────────

    fun add(
        customerName: String,
        date: LocalDate,
        startTime: LocalTime,
        durationMinutes: Int,
        description: String
    ): WaitlistEntry {
        require(customerName.isNotBlank()) { "Customer name cannot be empty." }
        require(description.isNotBlank()) { "Description cannot be empty." }
        require(durationMinutes > 0) { "Duration must be positive." }
        require(!date.isBefore(LocalDate.now())) { "Cannot waitlist a past date." }

        val entry = WaitlistEntry(customerName, date, startTime, durationMinutes, description)
        entries.add(entry)
        service.auditLog.log(
            "WL:${entry.id}", AuditLog.Action.WAITLISTED,
            "$customerName for $date $startTime (${durationMinutes}m)"
        )
        return entry
    }

    // ── Query ──────────────────────────────────────────────────────

    fun list(): List<WaitlistEntry> = entries.toList()

    fun find(id: String): WaitlistEntry? = entries.firstOrNull { it.id == id }

    fun size(): Int = entries.size

    // ── Remove ─────────────────────────────────────────────────────

    fun remove(id: String): Boolean = entries.removeIf { it.id == id }

    // ── Promote ────────────────────────────────────────────────────

    /**
     * Walks the queue front-to-back; for each entry, if a fresh validation
     * pass succeeds, creates the booking and removes the entry. Returns the
     * promoted bookings in promotion order. Each promotion narrows capacity
     * for subsequent entries, so naturally caps at the available slots.
     */
    fun tryPromoteAll(): List<Booking> {
        val promoted = mutableListOf<Booking>()
        val iter = entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val v = validator.validateNewBooking(
                e.customerName, e.date, e.startTime, e.durationMinutes, e.description
            )
            if (!v.valid) continue

            try {
                val booking = service.createBooking(
                    e.customerName, e.date, e.startTime, e.durationMinutes, e.description
                )
                service.auditLog.log(
                    booking.id, AuditLog.Action.PROMOTED,
                    "Promoted from waitlist entry ${e.id}"
                )
                promoted.add(booking)
                iter.remove()
            } catch (_: Exception) {
                // Promotion failed; entry remains in the queue for the next tryPromoteAll pass.
            }
        }
        return promoted

}
