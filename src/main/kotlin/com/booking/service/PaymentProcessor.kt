package com.booking.service

import com.booking.model.PaymentIntent

/**
 * Pluggable payment gateway. Implementations adapt the booking system's
 * intent model to a real provider (Stripe, Adyen, …). Each call should be
 * idempotent for the same intent id.
 */
interface PaymentProcessor {

    data class Result(
        val approved: Boolean,
        val processorReference: String,
        val failureReason: String? = null
    )

    /** Charge the intent. Implementations must not mutate the intent. */
    fun confirm(intent: PaymentIntent): Result

    /** Refund a previously succeeded intent. */
    fun refund(intent: PaymentIntent): Result
}

/**
 * In-memory test processor. Approves every confirm/refund unless the
 * customer used a coupon containing "FAIL" (a deterministic hook for
 * exercising the failure path without random behaviour).
 */
class MockPaymentProcessor : PaymentProcessor {

    private var counter = 0
    private val declineMarkers = mutableSetOf<String>()

    /** Force the next confirm() for [intentId] to decline with [reason]. */
    fun forceDecline(intentId: String, reason: String = "card_declined") {
        declineMarkers.add("$intentId|$reason")
    }

    override fun confirm(intent: PaymentIntent): PaymentProcessor.Result {
        counter += 1
        val ref = "ch_mock_%06d".format(counter)
        val pending = declineMarkers.firstOrNull { it.startsWith("${intent.id}|") }
        if (pending != null) {
            declineMarkers.remove(pending)
            val reason = pending.substringAfter("|")
            return PaymentProcessor.Result(false, ref, reason)
        }
        return PaymentProcessor.Result(true, ref)
    }

    override fun refund(intent: PaymentIntent): PaymentProcessor.Result {
        counter += 1
        return PaymentProcessor.Result(true, "re_mock_%06d".format(counter))
    }
}
