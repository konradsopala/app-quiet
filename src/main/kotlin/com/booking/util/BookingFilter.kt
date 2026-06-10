package com.booking.util

import com.booking.model.Booking
import java.time.LocalDate
import java.util.Objects

/**
 * Fluent, chainable sort/filter utility for advanced booking queries.
 *
 * Operates on any list of bookings, allowing filtering by status, date range, and
 * customer name, with configurable sort order and result limiting.
 *
 * The constructor makes a defensive copy of the input list so external mutations
 * do not affect internal state.
 */
class BookingFilter(bookings: List<Booking>) {

    enum class SortField {
        DATE, CUSTOMER_NAME, STATUS
    }

    private val source: List<Booking> = bookings.toList()
    private var statusFilter: Booking.Status? = null
    private var fromDate: LocalDate? = null
    private var toDate: LocalDate? = null
    private var customerPattern: String? = null
    private var requiredTags: MutableSet<String> = mutableSetOf()
    private var referencePattern: String? = null
    private var sortField: SortField = SortField.DATE
    private var ascending: Boolean = true
    private var limit: Int = 0

    init {
        Objects.requireNonNull(bookings, "bookings must not be null")
    }

    // ── Fluent filter setters ────────────────────────────────────

    fun byStatus(status: Booking.Status): BookingFilter { statusFilter = status; return this }

    fun fromDate(from: LocalDate): BookingFilter { fromDate = from; return this }

    fun toDate(to: LocalDate): BookingFilter { toDate = to; return this }

    fun byCustomer(pattern: String): BookingFilter { customerPattern = pattern; return this }

    /**
     * Keep only bookings that carry [tag] (case-insensitive). Multiple
     * calls AND-compose — `byTag("private").byTag("vip")` matches only
     * bookings that have **both** tags.
     */
    fun byTag(tag: String): BookingFilter {
        val normalised = tag.trim().lowercase()
        if (normalised.isNotEmpty()) requiredTags.add(normalised)
        return this
    }

    /** Keep only bookings whose [Booking.internalReference] contains [pattern] (case-insensitive). */
    fun byInternalReference(pattern: String): BookingFilter {
        referencePattern = pattern
        return this
    }

    fun sortBy(field: SortField, ascending: Boolean): BookingFilter {
        this.sortField = field
        this.ascending = ascending
        return this
    }

    fun limit(max: Int): BookingFilter { limit = max; return this }

    // ── Execute: apply all filters and sort ──────────────────────

    fun apply(): List<Booking> {
        var result = source.asSequence()

        statusFilter?.let { status ->
            result = result.filter { it.status == status }
        }
        fromDate?.let { from ->
            result = result.filter { !it.date.isBefore(from) }
        }
        toDate?.let { to ->
            result = result.filter { !it.date.isAfter(to) }
        }
        customerPattern?.takeIf { it.isNotBlank() }?.let { pattern ->
            val lower = pattern.lowercase()
            result = result.filter { it.customerName.lowercase().contains(lower) }
        }
        if (requiredTags.isNotEmpty()) {
            result = result.filter { booking ->
                // Booking.tags is already normalised to lower-case on add.
                requiredTags.all { it in booking.tags }
            }
        }
        referencePattern?.takeIf { it.isNotBlank() }?.let { pattern ->
            val lower = pattern.lowercase()
            result = result.filter { it.internalReference?.lowercase()?.contains(lower) == true }
        }

        val comparator: Comparator<Booking> = when (sortField) {
            SortField.DATE -> compareBy { it.date }
            SortField.CUSTOMER_NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.customerName }
            SortField.STATUS -> compareBy { it.status.name }
        }
        val finalComparator = if (ascending) comparator else comparator.reversed()
        var sorted = result.sortedWith(finalComparator)

        if (limit > 0) {
            sorted = sorted.take(limit)
        }

        return sorted.toList()
    }

    // ── Convenience: count results ───────────────────────────────

    fun count(): Long = apply().size.toLong()

    // ── Convenience: formatted numbered list ─────────────────────

    fun formatResults(): String {
        val results = apply()
        if (results.isEmpty()) return "No bookings match the criteria."
        val sb = StringBuilder("Found ${results.size} booking(s):\n")
        results.forEachIndexed { i, b ->
            sb.appendLine("  ${i + 1}. $b")
        }
        return sb.toString()
    }
}
