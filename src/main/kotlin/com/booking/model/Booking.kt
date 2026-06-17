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
 *   * [resourceId] — id of the [com.booking.model.Resource] this booking
 *     occupies. The validator counts overlapping bookings per-resource
 *     when enforcing capacity, so two bookings on different resources
 *     can share the same time slot. `null` means "no specific resource"
 *     and is treated as if the booking is on the system default resource
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
    resourceId: String? = null
) {
    enum class Status {
        CONFIRMED, CANCELLED
    }

    val id: String = UUID.randomUUID().toString()
    var status: Status = Status.CONFIRMED
        private set

    var quote: Quote? = null
        internal set

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
        val resourceSuffix = resourceId?.let { " | res:$it" } ?: ""
        return "[$id] $customerName | $date $startTime-$endTime | $description | " +
            "$status$priceSuffix$seriesSuffix$tagSuffix$refSuffix$resourceSuffix"
    }
}
