package com.booking.model

import java.time.LocalDate

/**
 * Discount applied via a coupon code.
 *
 * A sealed hierarchy so [BookingPricer] never has to know the concrete
 * arithmetic — it just calls [Coupon.applyTo].
 */
sealed class CouponDiscount {

    abstract fun apply(price: Double): Double

    /** Subtract a fixed amount (clamped at 0). */
    data class Flat(val amount: Double) : CouponDiscount() {
        init {
            require(amount > 0) { "Flat discount must be positive" }
        }
        override fun apply(price: Double): Double = (price - amount).coerceAtLeast(0.0)
    }

    /** Subtract a percentage of the running price. [percent] is 1..100. */
    data class Percent(val percent: Int) : CouponDiscount() {
        init {
            require(percent in 1..100) { "Percent discount must be in 1..100" }
        }
        override fun apply(price: Double): Double = price * (1.0 - percent / 100.0)
    }
}

/**
 * A redeemable discount code.
 *
 * Coupons are looked up by [code] (case-insensitive — stored upper-case).
 * Three optional guards narrow when the coupon is redeemable:
 *
 *   * [validFrom] / [validUntil] — date window (inclusive); null = no bound
 *   * [maxUses] — total redemption limit across the system; null = unlimited
 *   * [requiresCustomerType] — must match exactly (REGULAR/VIP/CORPORATE),
 *     case-insensitive; null = any customer type
 *
 * [usedCount] is the live counter, mutated by [CouponService.redeem].
 */
class Coupon(
    code: String,
    val discount: CouponDiscount,
    val validFrom: LocalDate? = null,
    val validUntil: LocalDate? = null,
    val maxUses: Int? = null,
    requiresCustomerType: String? = null
) {
    val code: String = code.trim().uppercase().also {
        require(it.isNotEmpty()) { "Coupon code cannot be empty" }
        require(it.length <= 20) { "Coupon code cannot exceed 20 characters" }
    }

    val requiresCustomerType: String? = requiresCustomerType?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    var usedCount: Int = 0
        internal set

    init {
        if (validFrom != null && validUntil != null) {
            require(!validFrom.isAfter(validUntil)) {
                "validFrom ($validFrom) must be on or before validUntil ($validUntil)"
            }
        }
        if (maxUses != null) {
            require(maxUses >= 1) { "maxUses must be >= 1" }
        }
    }

    /** True if [today] falls inside the (possibly half-open) validity window. */
    fun isCurrentlyValidOn(today: LocalDate): Boolean {
        if (validFrom != null && today.isBefore(validFrom)) return false
        if (validUntil != null && today.isAfter(validUntil)) return false
        return true
    }

    /** True if the coupon still has redemptions left (or is unlimited). */
    fun hasRemainingUses(): Boolean = maxUses == null || usedCount < maxUses

    /**
     * Reasons the coupon would currently reject [today] / [customerType].
     * Empty list means it would be accepted. Useful for the CLI to show
     * *why* a redemption was rejected instead of a generic message.
     */
    fun rejectionReasons(today: LocalDate, customerType: String?): List<String> {
        val reasons = mutableListOf<String>()
        if (!isCurrentlyValidOn(today)) {
            val window = listOfNotNull(
                validFrom?.let { "from $it" },
                validUntil?.let { "until $it" }
            ).joinToString(" ")
            reasons.add("Outside validity window ($window).")
        }
        if (!hasRemainingUses()) {
            reasons.add("Max uses ($maxUses) reached.")
        }
        if (requiresCustomerType != null && customerType?.trim()?.uppercase() != requiresCustomerType) {
            reasons.add("Requires customer type $requiresCustomerType.")
        }
        return reasons
    }

    override fun toString(): String {
        val windowStr = listOfNotNull(
            validFrom?.let { "from=$it" },
            validUntil?.let { "until=$it" }
        ).joinToString(" ")
        val usesStr = if (maxUses == null) "uses=$usedCount" else "uses=$usedCount/$maxUses"
        val typeStr = requiresCustomerType?.let { " requires=$it" } ?: ""
        return "[$code] $discount${if (windowStr.isEmpty()) "" else " $windowStr"} $usesStr$typeStr"
    }
}
