package com.booking.service

import com.booking.config.AppConfig
import com.booking.model.Booking
import com.booking.model.PaymentIntent
import java.time.LocalDateTime

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

        val result = processor.refund(intent)
        check(result.approved) { "Processor refused refund: ${result.failureReason ?: "unknown"}" }
        intent.status = PaymentIntent.Status.REFUNDED
        intent.processorReference = result.processorReference
        service.auditLog.log(
            intent.bookingId, AuditLog.Action.PAYMENT_REFUNDED,
            "Intent: ${intent.id}, Ref: ${result.processorReference}, Amount: $%.2f %s"
                .format(intent.amount, intent.currency)
        )
        return intent
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
     */
    data class BulkRefundResult(
        val refunded: List<PaymentIntent>,
        val failures: List<Pair<PaymentIntent, String>>
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
            } catch (e: Exception) {
                failures.add(intent to (e.message ?: e::class.simpleName ?: "unknown"))
            }
        }
        return BulkRefundResult(refunded, failures)
    }

    /**
     * Sum of currently-held funds across all intents, regardless of currency.
     * Refunded intents contribute 0 (charge + refund cancel out); only intents
     * still in SUCCEEDED state count.
     */
    fun netSettled(): Double =
        intents.values
            .filter { it.status == PaymentIntent.Status.SUCCEEDED }
            .sumOf { it.amount }
}
