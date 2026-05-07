package com.booking.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Stripe-style payment intent attached to a booking's quote.
 *
 * The intent moves through a small state machine:
 *   REQUIRES_CONFIRMATION → SUCCEEDED → REFUNDED
 *                       └→ FAILED
 *
 * Amount is captured at intent creation time (frozen even if the underlying
 * quote later changes) so accounting reflects what was actually charged.
 */
class PaymentIntent(
    val bookingId: String,
    val amount: Double,
    val currency: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    enum class Status {
        REQUIRES_CONFIRMATION, SUCCEEDED, FAILED, REFUNDED
    }

    val id: String = "pi_" + UUID.randomUUID().toString().replace("-", "").take(24)

    var status: Status = Status.REQUIRES_CONFIRMATION
        internal set

    var processorReference: String? = null
        internal set

    var failureReason: String? = null
        internal set

    var settledAt: LocalDateTime? = null
        internal set

    override fun toString(): String {
        val ref = processorReference?.let { " ref:$it" } ?: ""
        val reason = failureReason?.let { " ($it)" } ?: ""
        return "[$id] booking:$bookingId %.2f %s | $status$ref$reason".format(amount, currency)
    }
}
