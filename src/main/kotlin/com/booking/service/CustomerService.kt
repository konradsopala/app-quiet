package com.booking.service

import com.booking.model.Customer

/**
 * In-memory directory of [Customer] records.
 *
 * The directory is intentionally independent of [BookingService]: a booking
 * can exist without a customer record (the legacy free-text name still
 * works), and a customer can exist without any bookings. The link is
 * resolved at read time by callers that care, so neither side has to
 * coordinate cleanup.
 */
class CustomerService {

    private val customers = linkedMapOf<String, Customer>()

    // ── Create ──────────────────────────────────────────────────────

    fun create(
        name: String,
        email: String? = null,
        phone: String? = null,
        loyaltyYears: Int = 0,
        notes: String = ""
    ): Customer {
        val customer = Customer(name, email, phone, loyaltyYears, notes)
        customers[customer.id] = customer
        return customer
    }

    // ── Read ────────────────────────────────────────────────────────

    fun find(id: String): Customer? = customers[id]

    fun list(): List<Customer> = customers.values.toList()

    /** Case-insensitive substring match against the customer name. */
    fun searchByName(needle: String): List<Customer> {
        val q = needle.lowercase().trim()
        if (q.isEmpty()) return emptyList()
        return customers.values.filter { it.name.lowercase().contains(q) }
    }

    /** Exact, case-insensitive lookup. Returns null if zero or >1 match. */
    fun findByEmail(email: String): Customer? {
        val needle = email.lowercase().trim()
        val matches = customers.values.filter { it.email?.lowercase() == needle }
        return matches.singleOrNull()
    }

    // ── Update ──────────────────────────────────────────────────────

    fun update(
        id: String,
        name: String? = null,
        email: String? = null,
        phone: String? = null,
        loyaltyYears: Int? = null,
        notes: String? = null
    ): Customer {
        val customer = customers[id]
            ?: throw IllegalArgumentException("Unknown customer: $id")
        name?.let {
            require(it.isNotBlank()) { "Customer name cannot be empty." }
            customer.name = it
        }
        email?.let {
            require(it.contains("@")) { "Email must contain @." }
            customer.email = it
        }
        phone?.let { customer.phone = it }
        loyaltyYears?.let {
            require(it >= 0) { "Loyalty years cannot be negative." }
            customer.loyaltyYears = it
        }
        notes?.let { customer.notes = it }
        customer.touch()
        return customer
    }

    // ── Delete ──────────────────────────────────────────────────────

    fun delete(id: String): Boolean = customers.remove(id) != null

    fun size(): Int = customers.size
}
