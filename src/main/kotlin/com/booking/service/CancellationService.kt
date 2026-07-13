package com.booking.service

import com.booking.model.Booking
import com.booking.model.CancellationPolicy
import com.booking.model.PaymentIntent
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Loyalty tenure (years) that earns the extra grace-refund bonus. */
private const val LOYALTY_BONUS_MIN_YEARS = 3

/** Extra refund percentage points granted to loyal customers on top of the tier. */
private const val LOYALTY_BONUS_PERCENT = 15

/**
 * Applies a [CancellationPolicy] to real bookings and payments.
 *
 * Two entry points:
 *   * [quote] — a pure preview: how much notice is being given, which policy
 *     tier applies, and the resulting fee / refund split. Mutates nothing, so
 *     the CLI can show "cancel now and you'll be refunded $X (fee $Y)" before
 *     the operator commits.
 *   * [cancel] — performs the cancellation: flips the booking to CANCELLED and
 *     hands back exactly the refundable share via [PaymentService.refundPartial],
 *     retaining the fee. The retained fee is recorded in the audit log.
 *
 * The "charged amount" a refund is computed against is what the customer can
 * actually get back — the sum of still-refundable settled payments for the
 * booking. When there are no payments the booking's quote total is used as an
 * advisory basis (the numbers are shown, but nothing is moved).
 */
class CancellationService(
    private val service: BookingService,
    private val payments: PaymentService,
    private val customers: CustomerService,
    val policy: CancellationPolicy = CancellationPolicy.DEFAULT
) {

    /**
     * Preview of what cancelling [bookingId] at [now] would cost.
     *
     *   * [noticeHours] — whole hours between [now] and the booking start
     *     (negative once the start has passed).
     *   * [isNoShow] — true when the start is already in the past.
     *   * [chargedAmount] — the refundable basis (settled payments, or the quote
     *     total when unpaid).
     *   * [hasPayments] — whether real money is attached (drives whether [cancel]
     *     actually moves funds).
     */
    data class Quote(
        val bookingId: String,
        val noticeHours: Long,
        val isNoShow: Boolean,
        val tierLabel: String,
        val refundPercent: Int,
        val chargedAmount: Double,
        val refundAmount: Double,
        val feeAmount: Double,
        val hasPayments: Boolean
    ) {
        override fun toString(): String {
            val when_ = if (isNoShow) "after start (no-show)" else "$noticeHours h notice"
            val basis = if (hasPayments) "paid" else "quoted (unpaid)"
            // tierLabel can contain a literal '%' (e.g. "100%"), so it must be a
            // format ARGUMENT (%s) rather than part of the template string.
            return "Cancellation of %s — %s, tier %s: refund $%.2f, fee $%.2f of $%.2f %s"
                .format(bookingId, when_, tierLabel, refundAmount, feeAmount, chargedAmount, basis)
        }
    }

    /** Outcome of an executed [cancel]: the preview plus the intents refunded. */
    data class Result(
        val quote: Quote,
        val refunded: List<PaymentIntent>
    )

    fun quote(bookingId: String, now: LocalDateTime = LocalDateTime.now()): Quote {
        val booking = service.findBooking(bookingId)
            ?: throw IllegalArgumentException("Booking not found.")
        val notice = noticeHours(booking, now)
        val isNoShow = notice < 0
        val basePercent = policy.refundPercentForNotice(notice)
        val percent = basePercent + loyaltyBonusPercent(booking.customerId)
        val settled = settledRefundable(bookingId)
        val hasPayments = settled > 0.0
        val charged = if (hasPayments) settled else (booking.quote?.total ?: 0.0)
        val refund = round2(charged * percent / 100.0)
        val fee = round2(charged - refund)
        return Quote(
            bookingId = bookingId,
            noticeHours = notice,
            isNoShow = isNoShow,
            tierLabel = policy.tierLabelForNotice(notice),
            refundPercent = percent,
            chargedAmount = round2(charged),
            refundAmount = refund,
            feeAmount = fee,
            hasPayments = hasPayments
        )
    }

    /**
     * Cancel [bookingId] under the policy. Returns null when the booking is
     * missing or already cancelled (so the caller can report it), otherwise a
     * [Result] carrying the applied [Quote] and any refunded intents. The
     * refundable share is returned via partial refunds; the fee is retained.
     */
    fun cancel(bookingId: String, now: LocalDateTime = LocalDateTime.now()): Result? {
        val booking = service.findBooking(bookingId) ?: return null
        if (booking.status == Booking.Status.CANCELLED) return null

        val quote = quote(bookingId, now)
        if (!service.cancelBooking(bookingId)) return null

        val refunded = if (quote.hasPayments && quote.refundAmount > 0.0) {
            payments.refundAmountForBooking(bookingId, quote.refundAmount)
        } else {
            emptyList()
        }
        // Record the policy outcome alongside the plain CANCELLED entry that
        // cancelBooking already logged, so the fee is auditable. The customer's
        // contact details are included so support can follow up on the refund
        // without a separate lookup.
        val contact = booking.customerId?.let { customers.find(it) }
            ?.let { c -> listOfNotNull(c.email, c.phone).joinToString(", ") }
            ?: "-"
        service.auditLog.log(
            bookingId, AuditLog.Action.CANCELLED,
            "Policy %s: refunded $%.2f, fee $%.2f (of $%.2f) | contact: %s"
                .format(quote.tierLabel, quote.refundAmount, quote.feeAmount, quote.chargedAmount, contact)
        )
        return Result(quote, refunded)
    }

    // ── internals ──────────────────────────────────────────────────

    /** Whole hours of notice between [now] and the booking's start instant. */
    private fun noticeHours(booking: Booking, now: LocalDateTime): Long {
        val start = LocalDateTime.of(booking.date, booking.startTime)
        return ChronoUnit.HOURS.between(now, start)
    }

    /** Extra refund percentage points for a loyal customer, 0 if unlinked or not yet eligible. */
    private fun loyaltyBonusPercent(customerId: String?): Int {
        val customer = customerId?.let { customers.find(it) } ?: return 0
        return if (customer.loyaltyYears >= LOYALTY_BONUS_MIN_YEARS) LOYALTY_BONUS_PERCENT else 0
    }

    /** Sum of still-refundable amounts across the booking's SUCCEEDED intents. */
    private fun settledRefundable(bookingId: String): Double =
        payments.listForBooking(bookingId)
            .filter { it.status == PaymentIntent.Status.SUCCEEDED }
            .sumOf { it.remainingRefundable }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}
