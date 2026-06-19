package com.booking.service

import com.booking.model.Booking

/**
 * Computes loyalty tiers and discount percentages from a customer's booking
 * history.
 *
 * Tiers are earned by cumulative *confirmed* booking count. The engine is pure
 * with respect to [BookingService]: it reads the current booking set and never
 * writes back. Discounts produced here are advisory — the pricing layer decides
 * whether to honour them.
 */
class LoyaltyEngine(private val service: BookingService) {

    /**
     * Loyalty tiers in ascending order. [threshold] is the minimum number of
     * confirmed bookings to reach the tier; [discount] is the fractional price
     * reduction granted (0.10 == 10% off).
     */
    enum class Tier(val threshold: Int, val discount: Double) {
        BRONZE(0, 0.00),
        SILVER(5, 0.05),
        GOLD(15, 0.10),
        PLATINUM(30, 0.15);

        fun discountPercent(): Int = (discount * 100).toInt()

        companion object {
            /** The highest tier whose [threshold] is met by [bookingCount]. */
            fun forCount(bookingCount: Int): Tier =
                entries.last { bookingCount >= it.threshold }

            /** The next tier above [tier], or null if already at the top. */
            fun next(tier: Tier): Tier? =
                entries.getOrNull(tier.ordinal + 1)
        }
    }

    /** Count of confirmed bookings attributable to [customerName]. */
    fun confirmedCount(customerName: String): Int =
        service.listBookings().count {
            it.customerName.equals(customerName, ignoreCase = true) &&
                it.status == Booking.Status.CONFIRMED
        }

    /** The current tier for [customerName]. */
    fun tierFor(customerName: String): Tier =
        Tier.forCount(confirmedCount(customerName))

    /** The advisory discount fraction for [customerName]. */
    fun discountFor(customerName: String): Double =
        tierFor(customerName).discount

    /** Apply the customer's discount to [amount], returning the net price. */
    fun applyDiscount(customerName: String, amount: Double): Double {
        require(amount >= 0.0) { "amount must not be negative." }
        return amount * (1.0 - discountFor(customerName))
    }

    /**
     * A progress snapshot toward the next tier, useful for "3 more bookings to
     * GOLD" style prompts.
     */
    fun progress(customerName: String): Progress {
        val count = confirmedCount(customerName)
        val tier = Tier.forCount(count)
        val next = Tier.next(tier)
        val remaining = next?.let { (it.threshold - count).coerceAtLeast(0) }
        return Progress(customerName, count, tier, next, remaining)
    }

    data class Progress(
        val customerName: String,
        val bookingCount: Int,
        val tier: Tier,
        val nextTier: Tier?,
        val bookingsToNext: Int?
    ) {
        override fun toString(): String {
            val base = "$customerName: ${tier.name} (${tier.discountPercent()}% off), " +
                "$bookingCount booking(s)"
            return when {
                nextTier == null -> "$base — top tier reached"
                bookingsToNext == 0 -> "$base — eligible for ${nextTier.name}"
                else -> "$base — $bookingsToNext to ${nextTier.name}"
            }
        }
    }
}
