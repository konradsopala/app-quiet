package com.booking.model

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Booking entity representing a single reservation in the system.
 *
 * Optional metadata fields layer on top of the core slot:
 *
 *   * [tags] — labels for grouping or routing (e.g. "private-room", "vip",
 *     "off-peak"). Surfaced to CSV/iCal export and the filter utility.
 *   * [notes] — staff-only free-text. **Not** included in iCal export, since
 *     the calendar is the customer-visible artefact.
 *   * [internalReference] — a free-form id from an upstream system (CRM,
 *     POS, etc.). Indexed for lookup via filter / search.
 *   * [customerId] — optional link to a [com.booking.model.Customer] record
 *     in the directory. When set, downstream callers (pricer, reports,
 *     exporters) can resolve richer info (loyalty years, email/phone)
 *     instead of relying on [customerName] alone.
 *   * [resourceId] — id of the [com.booking.model.Resource] this booking
 *     occupies. The validator counts overlapping bookings per-resource
 *     when enforcing capacity. `null` means "no specific resource" and
 *     is treated as if the booking sits on the system default resource
 *     for capacity purposes.
 *
 * All optional fields default to empty/null so callers don't have to
 * supply them; the existing call sites are unaffected.
 */
class Booking(
    val customerName: String,
    var date: LocalDate,
    var startTime: LocalTime,
    var durationMinutes: Int,
    var description: String,
    val seriesId: String? = null,
    tags: Set<String> = emptySet(),
    notes: String? = null,
    internalReference: String? = null,
    customerId: String? = null,
    resourceId: String? = null,
    /**
     * Optional id override. Default callers should not pass this — a UUID
     * is generated. Snapshot restore passes the persisted id so booking
     * references (audit log, customer linkage, etc.) keep resolving.
     */
    id: String? = null
) {
    enum class Status {
        CONFIRMED, CANCELLED
    }

    val id: String = id ?: UUID.randomUUID().toString()
    var status: Status = Status.CONFIRMED
        private set

    var quote: Quote? = null
        internal set

    /**
     * Restore status + quote from a persisted snapshot. The default
     * constructor always lands a booking in CONFIRMED with no quote;
     * this lets the persistence layer bring those back to their saved
     * values without re-running cancel() / attachQuote() side effects.
     */
    internal fun restoreState(status: Status, quote: Quote?) {
        this.status = status
        this.quote = quote
    }

    /**
     * Mutable copy so addTag/removeTag can edit it without recreating the
     * booking. Normalised to lower-case + trimmed at construction time so the
     * direct-set path matches what [addTag] would produce.
     */
    private val _tags: MutableSet<String> = tags
        .asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toMutableSet()
    val tags: Set<String> get() = _tags.toSet()

    var notes: String? = notes
    var internalReference: String? = internalReference
    var customerId: String? = customerId
    var resourceId: String? = resourceId

    val endTime: LocalTime
        get() = startTime.plusMinutes(durationMinutes.toLong())

    fun cancel() {
        status = Status.CANCELLED
    }

    /** Adds [tag] (normalised to lower-case). Returns true if the set was actually changed. */
    fun addTag(tag: String): Boolean {
        require(tag.isNotBlank()) { "tag cannot be blank" }
        require(',' !in tag) { "tag cannot contain a comma" }
        return _tags.add(tag.trim().lowercase())
    }

    fun removeTag(tag: String): Boolean = _tags.remove(tag.trim().lowercase())

    fun hasTag(tag: String): Boolean = _tags.contains(tag.trim().lowercase())

    /** True if this booking's time window on [other.date] overlaps [other]. */
    fun overlaps(other: Booking): Boolean {
        if (date != other.date) return false
        return startTime < other.endTime && other.startTime < endTime
    }

    override fun toString(): String {
        val priceSuffix = quote?.let { " | $%.2f".format(it.total) } ?: ""
        val seriesSuffix = seriesId?.let { " | series:$it" } ?: ""
        val tagSuffix = if (_tags.isEmpty()) "" else " | tags:[${_tags.sorted().joinToString(",")}]"
        val refSuffix = internalReference?.let { " | ref:$it" } ?: ""
        val customerSuffix = customerId?.let { " | cust:$it" } ?: ""
        val resourceSuffix = resourceId?.let { " | res:$it" } ?: ""
        return "[$id] $customerName | $date $startTime-$endTime | $description | " +
            "$status$priceSuffix$seriesSuffix$tagSuffix$refSuffix$customerSuffix$resourceSuffix"
    }
}
