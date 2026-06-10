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
        DATE, CUSTOMER_NAME, STATUS, DURATION, PRICE
    }

    private val source: List<Booking> = bookings.toList()
    private var statusFilter: Booking.Status? = null
    private var fromDate: LocalDate? = null
    private var toDate: LocalDate? = null
    private var customerPattern: String? = null
    private var minDurationMinutes: Int? = null
    private var maxDurationMinutes: Int? = null
    private var minPrice: Double? = null
    private var maxPrice: Double? = null
    private var customerTypeFilter: String? = null
    private var requireQuoted: Boolean = false
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

    /** Keep only bookings whose duration is at least [minutes] minutes. */
    fun byMinDuration(minutes: Int): BookingFilter {
        require(minutes >= 0) { "minutes must be >= 0" }
        minDurationMinutes = minutes
        return this
    }

    /** Keep only bookings whose duration is at most [minutes] minutes. */
    fun byMaxDuration(minutes: Int): BookingFilter {
        require(minutes >= 0) { "minutes must be >= 0" }
        maxDurationMinutes = minutes
        return this
    }

    /**
     * Keep only bookings with a quote whose total is in [min, max] (inclusive).
     * Either bound can be null to leave that side open. Bookings without a
     * quote are excluded — combining this with [requireQuoted] is redundant.
     */
    fun byPriceRange(min: Double? = null, max: Double? = null): BookingFilter {
        if (min != null && max != null) {
            require(min <= max) { "min ($min) must be <= max ($max)" }
        }
        minPrice = min
        maxPrice = max
        return this
    }

    /** Keep only bookings whose attached quote is for [type] (REGULAR/VIP/CORPORATE, case-insensitive). */
    fun byCustomerType(type: String): BookingFilter {
        customerTypeFilter = type.trim().uppercase()
        return this
    }

    /** Keep only bookings that have any quote attached (regardless of total). */
    fun onlyQuoted(): BookingFilter { requireQuoted = true; return this }

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
        minDurationMinutes?.let { min ->
            result = result.filter { it.durationMinutes >= min }
        }
        maxDurationMinutes?.let { max ->
            result = result.filter { it.durationMinutes <= max }
        }
        if (requireQuoted || minPrice != null || maxPrice != null || customerTypeFilter != null) {
            result = result.filter { it.quote != null }
        }
        minPrice?.let { min ->
            result = result.filter { (it.quote?.total ?: Double.NEGATIVE_INFINITY) >= min }
        }
        maxPrice?.let { max ->
            result = result.filter { (it.quote?.total ?: Double.POSITIVE_INFINITY) <= max }
        }
        customerTypeFilter?.let { t ->
            result = result.filter { it.quote?.customerType == t }
        }

        val comparator: Comparator<Booking> = when (sortField) {
            SortField.DATE -> compareBy { it.date }
            SortField.CUSTOMER_NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.customerName }
            SortField.STATUS -> compareBy { it.status.name }
            SortField.DURATION -> compareBy { it.durationMinutes }
            // Sort unquoted bookings last when ascending, first when descending,
            // by pushing them to +Infinity for the comparator's natural order.
            SortField.PRICE -> compareBy { it.quote?.total ?: Double.POSITIVE_INFINITY }
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
