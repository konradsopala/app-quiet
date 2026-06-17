package com.booking.service

import com.booking.config.AppConfig
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
 * Bookings live against a [com.booking.model.Resource]; the capacity
 * check that used to run against one global integer now runs per resource.
 * The legacy [capacity] property is preserved as a shortcut to the
 * default resource's cap so existing callers (CLI menu, stats screens)
 * keep compiling.
 */
class BookingService(private val config: AppConfig = AppConfig.DEFAULT) {

    private val bookings = linkedMapOf<String, Booking>()
    val auditLog = AuditLog()
    val resources: ResourceService = ResourceService(config, auditLog)

    /**
     * Shortcut for "capacity of the default resource".
     *
     * Returns the default resource's capacity, or [AppConfig.defaultCapacity]
     * when the default resource has been deleted. Setting the property
     * updates the default resource's capacity if it exists, otherwise
     * recreates one with that cap.
     */
    var capacity: Int
        get() = resources.defaultResource()?.capacity ?: config.defaultCapacity
        set(value) {
            require(value >= 1) { "Capacity must be at least 1." }
            val default = resources.defaultResource()
            if (default != null) {
                resources.setCapacity(default.id, value)
            } else {
                // Default was deleted at some point — recreate it with the
                // requested cap so future bookings have a fallback resource.
                resources.register(name = "Main", capacity = value)
            }
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
        internalReference: String? = null,
        resourceId: String? = null
    ): Booking {
        require(!date.isBefore(LocalDate.now())) { "Booking date cannot be in the past." }
        require(durationMinutes > 0) { "Duration must be positive." }
        // If a resourceId is supplied, it must point at a known resource;
        // an unknown id would silently drop the booking onto the default
        // and confuse downstream stats / exports.
        if (resourceId != null) {
            require(resources.find(resourceId) != null) {
                "Unknown resource id: $resourceId"
            }
        }
        val booking = Booking(
            customerName, date, startTime, durationMinutes, description, seriesId,
            tags, notes, internalReference, resourceId
        )
        bookings[booking.id] = booking
        val seriesNote = seriesId?.let { ", Series: $it" } ?: ""
        val tagsNote = if (booking.tags.isEmpty()) "" else ", Tags: ${booking.tags.sorted()}"
        val refNote = internalReference?.let { ", Ref: $it" } ?: ""
        val resourceNote = resourceId?.let { ", Resource: $it" } ?: ""
        auditLog.log(
            booking.id, AuditLog.Action.CREATED,
            "Customer: $customerName, Date: $date ${booking.startTime}-${booking.endTime}" +
                "$seriesNote$tagsNote$refNote$resourceNote"
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
     * window [start, start + durationMinutes].
     *
     * When [resourceId] is non-null, only bookings on the same resource
     * are returned (bookings on a *different* resource don't compete for
     * the same slot). Pass null to count overlaps across all resources
     * — useful for system-wide schedules / reports.
     *
     * A booking whose `resourceId` is null is treated as if it sits on
     * the system default resource: this matches the capacity-check
     * model where unresourced bookings draw from the same bucket as the
     * default.
     *
     * The [excludeId] is skipped so the same booking can be re-checked
     * when rescheduling itself.
     */
    fun overlappingBookings(
        date: LocalDate,
        start: LocalTime,
        durationMinutes: Int,
        excludeId: String? = null,
        resourceId: String? = null
    ): List<Booking> {
        val end = start.plusMinutes(durationMinutes.toLong())
        val effectiveResource = resourceId ?: ResourceService.MAIN_RESOURCE_ID
        return bookings.values.filter { b ->
            b.id != excludeId &&
                b.status == Booking.Status.CONFIRMED &&
                b.date == date &&
                b.startTime < end && start < b.endTime &&
                (resourceId == null ||
                    (b.resourceId ?: ResourceService.MAIN_RESOURCE_ID) == effectiveResource)
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
                "id,customer,date,start,end,description,status,quote_total," +
                    "tags,notes,internal_ref,resource_id"
            )
            for (b in bookings.values) {
                val quoteTotal = b.quote?.let { "%.2f".format(it.total) } ?: ""
                // Tags are joined with `;` rather than `,` so a single CSV cell
                // can hold the whole set without needing to be quoted (and so
                // a downstream parser can split on `;` cheaply).
                val tagsField = b.tags.sorted().joinToString(";")
                writer.printf(
                    "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    escape(b.id), escape(b.customerName),
                    b.date, b.startTime, b.endTime,
                    escape(b.description), b.status, quoteTotal,
                    escape(tagsField),
                    escape(b.notes ?: ""),
                    escape(b.internalReference ?: ""),
                    escape(b.resourceId ?: "")
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
