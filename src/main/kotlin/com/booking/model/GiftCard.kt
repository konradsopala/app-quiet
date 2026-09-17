package com.booking.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A prepaid stored-value card that can be redeemed against booking quotes.
 *
 * The card is a *liability*: money the business has already collected but
 * not yet earned. That framing drives the design —
 *
 *   * [initialValue] is frozen at issue time so the original sale can always
 *     be reconciled, while [balance] moves with redemptions and reloads.
 *   * Every balance movement is recorded as a [GiftCardTransaction] by
 *     [com.booking.service.GiftCardService]; this class never mutates its
 *     own balance outside the service, hence the `internal` setters.
 *   * The [code] is what the customer holds. It is generated (and check-
 *     digit validated) by [com.booking.util.GiftCardCodeGenerator] and
 *     stored in canonical `GC-XXXX-XXXX-XXXX` form. The [id] is the stable
 *     internal key used for audit/snapshot linkage and never shown to the
 *     customer.
 *
 * Lifecycle:
 *
 * ```
 *   ACTIVE ──redeem to zero──▶ DEPLETED ──reload──▶ ACTIVE
 *     │                           │
 *     ├──expiry sweep──▶ EXPIRED ◀┘
 *     └──void─────────▶ VOIDED
 * ```
 *
 * `EXPIRED` and `VOIDED` are terminal; the service refuses reloads and
 * redemptions against them.
 */
class GiftCard(
    val code: String,
    val initialValue: Double,
    val currency: String,
    /** Optional link to the [Customer] who bought the card. */
    val purchaserCustomerId: String? = null,
    /** Free-text recipient — gift cards are usually bought *for* someone else. */
    val recipientName: String? = null,
    val recipientEmail: String? = null,
    message: String? = null,
    val issuedAt: LocalDateTime = LocalDateTime.now(),
    /** Last day (inclusive) the card can be redeemed. `null` never expires. */
    val expiresAt: LocalDate? = null,
    /** Optional id override for snapshot restore; default callers don't pass it. */
    id: String? = null
) {
    enum class Status {
        ACTIVE, DEPLETED, EXPIRED, VOIDED;

        /** Whether the service may still move value on or off the card. */
        val terminal: Boolean get() = this == EXPIRED || this == VOIDED
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 200
        const val CODE_PATTERN = "GC-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}"

        /** Two doubles within this distance are treated as the same amount of money. */
        const val EPSILON = 0.005

        private val CODE_REGEX = Regex(CODE_PATTERN)
    }

    val id: String = id ?: ("gc_" + UUID.randomUUID().toString().replace("-", "").take(20))

    /** Trimmed; blank messages collapse to `null`. */
    val message: String? = message?.trim()?.takeIf { it.isNotEmpty() }

    var balance: Double = initialValue
        internal set

    var status: Status = Status.ACTIVE
        internal set

    var voidedAt: LocalDateTime? = null
        internal set

    var voidReason: String? = null
        internal set

    init {
        require(CODE_REGEX.matches(code)) { "Gift card code '$code' is not in canonical GC-XXXX-XXXX-XXXX form." }
        require(initialValue > 0.0) { "Gift card value must be positive, got $initialValue." }
        require(currency.length == 3) { "Currency must be a 3-letter ISO code, got '$currency'." }
        recipientEmail?.let { require(it.contains("@")) { "Recipient email must contain @." } }
        require(this.message == null || this.message.length <= MAX_MESSAGE_LENGTH) {
            "Gift message cannot exceed $MAX_MESSAGE_LENGTH characters."
        }
        expiresAt?.let {
            require(!it.isBefore(issuedAt.toLocalDate())) {
                "Expiry date $it cannot be before issue date ${issuedAt.toLocalDate()}."
            }
        }
    }

    /** True once the calendar has moved past [expiresAt]. Cards without an expiry never expire. */
    fun isExpiredOn(date: LocalDate): Boolean = expiresAt != null && date.isAfter(expiresAt)

    /**
     * Whether a redemption could succeed today: not terminal, not past its
     * expiry, and carrying a positive balance. The service still performs the
     * authoritative check (and expiry sweep) — this is for display and
     * pre-flight filtering.
     */
    fun isRedeemableOn(date: LocalDate): Boolean =
        status == Status.ACTIVE && !isExpiredOn(date) && balance > EPSILON

    /** Days until expiry from [date], or `null` when the card never expires. Negative once expired. */
    fun daysUntilExpiry(date: LocalDate): Long? =
        expiresAt?.let { ChronoUnit.DAYS.between(date, it) }

    /** Total value that has left the card so far (redemptions net of reversals, plus void/expiry write-offs). */
    val consumed: Double
        get() = (initialValue - balance).coerceAtLeast(0.0)

    /**
     * Restore mutable state from a persisted snapshot without replaying the
     * transactions that produced it.
     */
    internal fun restoreState(balance: Double, status: Status, voidedAt: LocalDateTime?, voidReason: String?) {
        require(balance >= 0.0) { "Restored balance cannot be negative." }
        this.balance = balance
        this.status = status
        this.voidedAt = voidedAt
        this.voidReason = voidReason
    }

    override fun toString(): String {
        val recipient = recipientName?.let { " for $it" } ?: ""
        val expiry = expiresAt?.let { " exp:$it" } ?: ""
        val reason = voidReason?.let { " ($it)" } ?: ""
        return "[$code] %.2f/%.2f %s | $status$recipient$expiry$reason".format(balance, initialValue, currency)
    }
}
