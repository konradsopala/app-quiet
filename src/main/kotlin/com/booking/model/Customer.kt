package com.booking.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Long-lived customer record.
 *
 * Bookings still carry a free-text `customerName` for backward compatibility,
 * but a booking may also be linked to a [Customer] by its [id]. When that
 * link exists, pricing and reporting code can read structured data
 * (loyalty years, contact details) instead of guessing from the string name.
 */
class Customer(
    var name: String,
    var email: String? = null,
    var phone: String? = null,
    var loyaltyYears: Int = 0,
    var notes: String = ""
) {
    val id: String = "cust_" + UUID.randomUUID().toString().replace("-", "").take(20)
    val createdAt: LocalDateTime = LocalDateTime.now()
    var updatedAt: LocalDateTime = createdAt
        internal set

    init {
        require(name.isNotBlank()) { "Customer name cannot be empty." }
        require(loyaltyYears >= 0) { "Loyalty years cannot be negative." }
        email?.let { require(it.contains("@")) { "Email must contain @." } }
    }

    fun touch() {
        updatedAt = LocalDateTime.now()
    }

    override fun toString(): String {
        val contact = listOfNotNull(email, phone).joinToString(", ").ifEmpty { "(no contact)" }
        val loyaltySuffix = if (loyaltyYears > 0) " | loyalty: ${loyaltyYears}y" else ""
        return "[$id] $name | $contact$loyaltySuffix"
    }
}
