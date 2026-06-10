package com.booking.service

import com.booking.model.Booking
import com.booking.model.Quote
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDate
import java.time.LocalTime

/**
 * Core service handling all booking CRUD operations and business logic.
 *
 * Backed by in-memory storage with insertion-order preservation.
 * All mutations are recorded in the embedded [AuditLog].
 *
 * [capacity] is the maximum number of confirmed bookings whose time windows
 * may overlap on the same day. Default 1 models a single resource.
 */
class BookingService {

    private val bookings = linkedMapOf<String, Booking>()
    val auditLog = AuditLog()

    var capacity: Int = 1
        set(value) {
            require(value >= 1) { "Capacity must be at least 1." }
            field = value
        }

    // ── Create ──────────────────────────────────────────────────────

    fun createBooking(
        customerName: String,
        date: LocalDate,
        startTime: LocalTime,
        durationMinutes: Int,
        description: String,
        seriesId: String? = null,
        tags: Set<String> = emptySet(),
        notes: String? = null,
        internalReference: String? = null
    ): Booking {
        require(!date.isBefore(LocalDate.now())) { "Booking date cannot be in the past." }
        require(durationMinutes > 0) { "Duration must be positive." }
        val booking = Booking(
            customerName, date, startTime, durationMinutes, description, seriesId,
            tags, notes, internalReference
        )
        bookings[booking.id] = booking
        val seriesNote = seriesId?.let { ", Series: $it" } ?: ""
        val tagsNote = if (booking.tags.isEmpty()) "" else ", Tags: ${booking.tags.sorted()}"
        val refNote = internalReference?.let { ", Ref: $it" } ?: ""
        auditLog.log(
            booking.id, AuditLog.Action.CREATED,
            "Customer: $customerName, Date: $date ${booking.startTime}-${booking.endTime}" +
                "$seriesNote$tagsNote$refNote"
        )
        return booking
    }

    // ── Series queries ──────────────────────────────────────────────

    fun findBySeries(seriesId: String): List<Booking> =
        bookings.values.filter { it.seriesId == seriesId }

    // ── Cancel ──────────────────────────────────────────────────────

    fun cancelBooking(id: String): Boolean {
        val booking = bookings[id] ?: return false
        if (booking.status == Booking.Status.CANCELLED) return false
        booking.cancel()
        auditLog.log(id, AuditLog.Action.CANCELLED, "Cancelled by user")
        return true
    }

    // ── Find ────────────────────────────────────────────────────────

    fun findBooking(id: String): Booking? = bookings[id]

    // ── List ────────────────────────────────────────────────────────

    fun listBookings(): List<Booking> = bookings.values.toList()

    // ── Search by customer name (case-insensitive, partial match) ──

    fun searchByCustomer(name: String): List<Booking> {
        val lowerName = name.lowercase()
        return bookings.values.filter { it.customerName.lowercase().contains(lowerName) }
    }

    // ── Update / reschedule ─────────────────────────────────────────

    fun updateBooking(
        id: String,
        newDate: LocalDate?,
        newStartTime: LocalTime?,
        newDurationMinutes: Int?,
        newDescription: String?
    ): Booking {
        val booking = bookings[id]
            ?: throw IllegalArgumentException("Booking not found.")
        check(booking.status != Booking.Status.CANCELLED) { "Cannot update a cancelled booking." }
        if (newDate != null) {
            require(!newDate.isBefore(LocalDate.now())) { "New date cannot be in the past." }
            booking.date = newDate
        }
        if (newStartTime != null) {
            booking.startTime = newStartTime
        }
        if (newDurationMinutes != null) {
            require(newDurationMinutes > 0) { "Duration must be positive." }
            booking.durationMinutes = newDurationMinutes
        }
        if (!newDescription.isNullOrBlank()) {
            booking.description = newDescription
        }
        auditLog.log(
            id, AuditLog.Action.UPDATED,
            "Date: ${booking.date} ${booking.startTime}-${booking.endTime}, Desc: ${booking.description}"
        )
        return booking
    }

    // ── Capacity / overlap helpers ──────────────────────────────────

    /**
     * Returns confirmed bookings whose time window on [date] overlaps the
     * window [start, start + durationMinutes]. The [excludeId] is skipped so
     * the same booking can be checked when rescheduling itself.
     */
    fun overlappingBookings(
        date: LocalDate,
        start: LocalTime,
        durationMinutes: Int,
        excludeId: String? = null
    ): List<Booking> {
        val end = start.plusMinutes(durationMinutes.toLong())
        return bookings.values.filter { b ->
            b.id != excludeId &&
                b.status == Booking.Status.CONFIRMED &&
                b.date == date &&
                b.startTime < end && start < b.endTime
        }
    }

    // ── Quote attachment ────────────────────────────────────────────

    fun attachQuote(id: String, quote: Quote): Booking {
        val booking = bookings[id]
            ?: throw IllegalArgumentException("Booking not found.")
        booking.quote = quote
        auditLog.log(
            id, AuditLog.Action.QUOTED,
            "Total: $%.2f, Type: ${quote.customerType}, Party: ${quote.partySize}".format(quote.total)
        )
        return booking
    }

    // ── Statistics ──────────────────────────────────────────────────

    fun getStatistics(): Map<String, Long> {
        val total = bookings.size.toLong()
        val confirmed = bookings.values.count { it.status == Booking.Status.CONFIRMED }.toLong()
        val cancelled = bookings.values.count { it.status == Booking.Status.CANCELLED }.toLong()
        return linkedMapOf("total" to total, "confirmed" to confirmed, "cancelled" to cancelled)
    }

    /** Sum of attached quote totals for confirmed bookings. */
    fun totalQuotedRevenue(): Double =
        bookings.values
            .filter { it.status == Booking.Status.CONFIRMED }
            .sumOf { it.quote?.total ?: 0.0 }

    // ── Export to CSV ───────────────────────────────────────────────

    fun exportToCsv(filePath: String) {
        PrintWriter(FileWriter(filePath)).use { writer ->
            writer.println(
                "id,customer,date,start,end,description,status,quote_total,tags,notes,internal_ref"
            )
            for (b in bookings.values) {
                val quoteTotal = b.quote?.let { "%.2f".format(it.total) } ?: ""
                // Tags are joined with `;` rather than `,` so a single CSV cell
                // can hold the whole set without needing to be quoted (and so
                // a downstream parser can split on `;` cheaply).
                val tagsField = b.tags.sorted().joinToString(";")
                writer.printf(
                    "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    escape(b.id), escape(b.customerName),
                    b.date, b.startTime, b.endTime,
                    escape(b.description), b.status, quoteTotal,
                    escape(tagsField),
                    escape(b.notes ?: ""),
                    escape(b.internalReference ?: "")
                )
            }
        }
        auditLog.log("SYSTEM", AuditLog.Action.EXPORTED, "Exported to $filePath")
    }

    private fun escape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
