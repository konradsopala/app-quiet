package com.booking.service

import com.booking.config.AppConfig
import com.booking.model.Booking
import com.booking.model.PaymentIntent
import java.time.LocalDateTime

/**
 * Exception for refund failures where the processor call's outcome is
 * indeterminate — the refund may or may not have been applied on the
 * processor side, so reconciliation is required before proceeding.
 */
class IndeterminateRefundException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Coordinates payment intents with booking state.
 *
 * Workflow:
 *   1. [createIntent] — booking must have a quote attached.
 *   2. [confirm]      — delegates to the [PaymentProcessor]; on success the
 *                       intent moves to SUCCEEDED, on failure to FAILED.
 *   3. [refund]       — only valid for SUCCEEDED intents.
 *
 * All transitions are recorded in [BookingService.auditLog].
 */
class PaymentService(
    private val service: BookingService,
    private val processor: PaymentProcessor,
    private val defaultCurrency: String = AppConfig.DEFAULT.defaultCurrency
) {

    private val intents = linkedMapOf<String, PaymentIntent>()

    /** Replace the in-memory intents map. Used by snapshot restore. */
    internal fun replaceAll(newIntents: List<PaymentIntent>) {
        intents.clear()
        for (i in newIntents) intents[i.id] = i
    }

    fun createIntent(bookingId: String, currency: String = defaultCurrency): PaymentIntent {
        val booking = service.findBooking(bookingId)
            ?: throw IllegalArgumentException("Unknown bookingId: $bookingId")
        check(booking.status == Booking.Status.CONFIRMED) {
            "Cannot create payment intent for a cancelled booking."
        }
        val quote = booking.quote
            ?: throw IllegalStateException("Booking has no quote — call quote price first.")
        require(currency.length == 3) { "Currency must be a 3-letter ISO code." }

        val intent = PaymentIntent(bookingId, quote.total, currency.uppercase())
        intents[intent.id] = intent
        service.auditLog.log(
            bookingId, AuditLog.Action.PAYMENT_INTENT_CREATED,
            "Intent: ${intent.id}, Amount: $%.2f %s".format(intent.amount, intent.currency)
        )
        return intent
    }

    fun confirm(intentId: String): PaymentIntent {
        val intent = intents[intentId]
            ?: throw IllegalArgumentException("Unknown payment intent: $intentId")
        check(intent.status == PaymentIntent.Status.REQUIRES_CONFIRMATION) {
            "Intent is in state ${intent.status}; only REQUIRES_CONFIRMATION can be confirmed."
        }

        val result = processor.confirm(intent)
        intent.processorReference = result.processorReference
        if (result.approved) {
            intent.status = PaymentIntent.Status.SUCCEEDED
            intent.settledAt = LocalDateTime.now()
            service.auditLog.log(
                intent.bookingId, AuditLog.Action.PAYMENT_SUCCEEDED,
                "Intent: ${intent.id}, Ref: ${result.processorReference}, Amount: $%.2f %s"
                    .format(intent.amount, intent.currency)
            )
        } else {
            intent.status = PaymentIntent.Status.FAILED
            intent.failureReason = result.failureReason ?: "unknown"
            service.auditLog.log(
                intent.bookingId, AuditLog.Action.PAYMENT_FAILED,
                "Intent: ${intent.id}, Reason: ${intent.failureReason}"
            )
        }
        return intent
    }

    fun refund(intentId: String): PaymentIntent {
        val intent = intents[intentId]
            ?: throw IllegalArgumentException("Unknown payment intent: $intentId")
        check(intent.status == PaymentIntent.Status.SUCCEEDED) {
            "Only SUCCEEDED intents can be refunded; current state: ${intent.status}."
        }

        val result = try {
            processor.refund(intent, intent.remainingRefundable)
        } catch (e: Exception) {
            throw IndeterminateRefundException(
                "Processor refund outcome indeterminate for intent ${intent.id}: call failed with ${e.message ?: e::class.simpleName}",
                e
            )
        }
        check(result.approved) { "Processor refused refund: ${result.failureReason ?: "unknown"}" }
        intent.refundedAmount = intent.amount
        intent.status = PaymentIntent.Status.REFUNDED
        intent.processorReference = result.processorReference
        service.auditLog.log(
            intent.bookingId, AuditLog.Action.PAYMENT_REFUNDED,
            "Intent: ${intent.id}, Ref: ${result.processorReference}, Amount: $%.2f %s"
                .format(intent.amount, intent.currency)
        )
        return intent
    }

    /**
     * Refund part (or the remainder) of a settled intent — used by the
     * cancellation-policy flow, which returns only the refundable share and
     * retains the rest as a fee. [amount] must be positive and no larger than
     * the intent's [PaymentIntent.remainingRefundable]. When the cumulative
     * refund reaches the full [PaymentIntent.amount] the intent moves to
     * REFUNDED; otherwise it stays SUCCEEDED with a raised
     * [PaymentIntent.refundedAmount].
     */
    fun refundPartial(intentId: String, amount: Double): PaymentIntent {
        val intent = intents[intentId]
            ?: throw IllegalArgumentException("Unknown payment intent: $intentId")
        check(intent.status == PaymentIntent.Status.SUCCEEDED) {
            "Only SUCCEEDED intents can be refunded; current state: ${intent.status}."
        }
        require(amount > 0.0) { "Refund amount must be positive." }
        require(amount <= intent.remainingRefundable + 1e-9) {
            "Refund $%.2f exceeds remaining refundable $%.2f."
                .format(amount, intent.remainingRefundable)
        }

        val result = try {
            processor.refund(intent, amount)
        } catch (e: Exception) {
            throw IndeterminateRefundException(
                "Processor refund outcome indeterminate for intent ${intent.id}: call failed with ${e.message ?: e::class.simpleName}",
                e
            )
        }
        check(result.approved) { "Processor refused refund: ${result.failureReason ?: "unknown"}" }
        intent.refundedAmount = (intent.refundedAmount + amount).coerceAtMost(intent.amount)
        intent.processorReference = result.processorReference
        val fullyRefunded = intent.remainingRefundable <= 1e-9
        if (fullyRefunded) intent.status = PaymentIntent.Status.REFUNDED
        service.auditLog.log(
            intent.bookingId, AuditLog.Action.PAYMENT_REFUNDED,
            "Intent: ${intent.id}, %s refund $%.2f %s (cumulative $%.2f)"
                .format(if (fullyRefunded) "final" else "partial", amount, intent.currency, intent.refundedAmount)
        )
        return intent
    }

    /**
     * Refund a target [totalAmount] across a booking's still-refundable
     * SUCCEEDED intents, largest-remaining first, stopping once the target is
     * met. Used by the cancellation-policy engine to hand back exactly the
     * refundable share. A [totalAmount] of 0 (or no refundable intents) is a
     * no-op. Failures from the processor are caught per-intent — like
     * [refundAllForBooking] — so one bad refund doesn't lose track of intents
     * already refunded before it; both are reported in the result.
     */
    fun refundAmountForBooking(bookingId: String, totalAmount: Double): BulkRefundResult {
        if (totalAmount <= 0.0) return BulkRefundResult(emptyList(), emptyList())
        val candidates = intents.values
            .filter { it.bookingId == bookingId && it.status == PaymentIntent.Status.SUCCEEDED }
            .sortedByDescending { it.remainingRefundable }
        val touched = mutableListOf<PaymentIntent>()
        val failures = mutableListOf<Pair<PaymentIntent, String>>()
        var outstanding = totalAmount
        for (intent in candidates) {
            if (outstanding <= 1e-9) break
            val take = minOf(outstanding, intent.remainingRefundable)
            if (take <= 1e-9) continue
            try {
                touched.add(refundPartial(intent.id, take))
                outstanding -= take
            } catch (e: IndeterminateRefundException) {
                // Indeterminate failure: processor call outcome is ambiguous, so we
                // must STOP processing further refunds (outstanding must not be reused)
                // and surface this for reconciliation.
                return BulkRefundResult(
                    touched,
                    failures,
                    indeterminateFailure = intent to (e.message ?: "indeterminate refund failure")
                )
            } catch (e: Exception) {
                // Definitively unprocessed failure: record it and continue with remaining intents.
                failures.add(intent to (e.message ?: e::class.simpleName ?: "unknown"))
            }
        }
        return BulkRefundResult(touched, failures)
    }

    fun find(intentId: String): PaymentIntent? = intents[intentId]

    fun list(): List<PaymentIntent> = intents.values.toList()

    fun listForBooking(bookingId: String): List<PaymentIntent> =
        intents.values.filter { it.bookingId == bookingId }

    /**
     * Refund every SUCCEEDED intent attached to [bookingId]. Used by the
     * cancel flow so a paid booking doesn't leave funds held when the slot
     * goes away. Failures from the processor are caught per-intent so one
     * bad refund doesn't block the others; they are returned in [failures].
     * Indeterminate failures (where the processor call outcome is ambiguous)
     * are reported separately in [indeterminateFailure] and stop further
     * processing to prevent issuing duplicate refunds.
     */
    data class BulkRefundResult(
        val refunded: List<PaymentIntent>,
        val failures: List<Pair<PaymentIntent, String>>,
        val indeterminateFailure: Pair<PaymentIntent, String>? = null
    )

    fun refundAllForBooking(bookingId: String): BulkRefundResult {
        val targets = intents.values.filter {
            it.bookingId == bookingId && it.status == PaymentIntent.Status.SUCCEEDED
        }
        val refunded = mutableListOf<PaymentIntent>()
        val failures = mutableListOf<Pair<PaymentIntent, String>>()
        for (intent in targets) {
            try {
                refunded.add(refund(intent.id))
            } catch (e: IndeterminateRefundException) {
                // Indeterminate failure: stop processing further intents and surface
                // this for reconciliation.
                return BulkRefundResult(
                    refunded,
                    failures,
                    indeterminateFailure = intent to (e.message ?: "indeterminate refund failure")
                )
            } catch (e: Exception) {
                failures.add(intent to (e.message ?: e::class.simpleName ?: "unknown"))
            }
        }
        return BulkRefundResult(refunded, failures)
    }

    /**
     * Sum of currently-held funds across all intents, regardless of currency.
     * Fully-refunded intents contribute 0; a partially-refunded (still
     * SUCCEEDED) intent contributes only its un-refunded remainder, so a
     * retained cancellation fee is reflected correctly.
     */
    fun netSettled(): Double =
        intents.values
            .filter { it.status == PaymentIntent.Status.SUCCEEDED }
            .sumOf { it.remainingRefundable }
}
