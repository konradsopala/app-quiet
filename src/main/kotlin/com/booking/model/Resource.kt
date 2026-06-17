package com.booking.model

import java.util.UUID

/**
 * A discrete bookable thing — a room, a piece of equipment, a staff
 * member — with its own concurrency limit.
 *
 * Bookings carry an optional [com.booking.model.Booking.resourceId];
 * the capacity check in [com.booking.service.BookingValidator] then
 * counts overlapping bookings **per resource** rather than against one
 * global cap. A resource with [capacity] > 1 (e.g. a 6-seat boardroom)
 * can host that many concurrent bookings before turning new ones away.
 *
 * A default resource (`MAIN`) is auto-created by [com.booking.service.ResourceService]
 * so the legacy "no resource specified" code paths still have somewhere
 * to land — preserving backward compatibility for callers that never
 * supplied a resource id.
 */
class Resource(
    name: String,
    capacity: Int = 1,
    val id: String = "res_" + UUID.randomUUID().toString().replace("-", "").take(16)
) {
    var name: String = name
        set(value) {
            require(value.isNotBlank()) { "Resource name cannot be blank." }
            field = value.trim()
        }

    var capacity: Int = capacity
        set(value) {
            require(value >= 1) { "Resource capacity must be at least 1." }
            field = value
        }

    init {
        require(name.isNotBlank()) { "Resource name cannot be blank." }
        require(capacity >= 1) { "Resource capacity must be at least 1." }
        this.name = name.trim()
    }

    override fun toString(): String = "[$id] $name (capacity: $capacity)"
}
