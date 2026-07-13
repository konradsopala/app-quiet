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
    val createdAt: LocalDateTime = LocalDateTime.now(),
    /** Optional id override for snapshot restore; default callers don't pass it. */
    id: String? = null
) {
    enum class Status {
        REQUIRES_CONFIRMATION, SUCCEEDED, FAILED, REFUNDED
    }

    val id: String = id ?: ("pi_" + UUID.randomUUID().toString().replace("-", "").take(24))

    var status: Status = Status.REQUIRES_CONFIRMATION
        internal set

    var processorReference: String? = null
        internal set

    var failureReason: String? = null
        internal set

    var settledAt: LocalDateTime? = null
        internal set

    /**
     * Cumulative amount refunded so far. Stays 0.0 for a plain SUCCEEDED intent;
     * a partial refund (e.g. a cancellation-policy fee retained) bumps it while
     * the intent remains SUCCEEDED. Once it reaches [amount] the intent moves to
     * REFUNDED. Frozen alongside [amount] so accounting reflects real movements.
     */
    var refundedAmount: Double = 0.0
        internal set

    /** Amount still eligible to be refunded ([amount] minus what's already back). */
    val remainingRefundable: Double
        get() = (amount - refundedAmount).coerceAtLeast(0.0)

    override fun toString(): String {
        val ref = processorReference?.let { " ref:$it" } ?: ""
        val reason = failureReason?.let { " ($it)" } ?: ""
        val refunded = if (refundedAmount > 0.0 && status != Status.REFUNDED)
            " refunded:%.2f".format(refundedAmount) else ""
        return "[$id] booking:$bookingId %.2f %s | $status$ref$reason$refunded".format(amount, currency)
    }
}
