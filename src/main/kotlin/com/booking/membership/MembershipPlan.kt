package com.booking.membership

import java.util.UUID

/**
 * A purchasable membership tier.
 *
 * Benefits are expressed in two ways:
 *
 *   * [includedMinutesPerPeriod] — a booking-minutes allowance granted at the
 *     start of every period. Unused minutes roll over up to
 *     [rolloverCapPercent] of the allowance.
 *   * [bookingDiscountPercent] — an ongoing percentage discount that pricing
 *     code can read via [MembershipService.discountFor].
 *
 * All money is in cents.
 */
class MembershipPlan(
    name: String,
    val interval: BillingInterval,
    val priceCents: Long,
    val setupFeeCents: Long = 0,
    val trialDays: Int = 0,
    val includedMinutesPerPeriod: Int = 0,
    val rolloverCapPercent: Int = 0,
    val bookingDiscountPercent: Int = 0,
    val maxMembers: Int? = null,
    features: Set<String> = emptySet(),
    id: String? = null,
) {
    val id: String = id ?: ("plan_" + UUID.randomUUID().toString().replace("-", "").take(12))
    val name: String = name.trim()

    var active: Boolean = true
        internal set

    private val _features: MutableSet<String> = features
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toMutableSet()
    val features: Set<String> get() = _features.toSet()

    init {
        require(this.name.isNotEmpty()) { "Plan name cannot be empty." }
        require(this.name.length <= 40) { "Plan name cannot exceed 40 characters." }
        require(priceCents >= 0) { "Plan price cannot be negative." }
        require(setupFeeCents >= 0) { "Setup fee cannot be negative." }
        require(trialDays in 0..365) { "Trial length must be 0..365 days." }
        require(includedMinutesPerPeriod >= 0) { "Included minutes cannot be negative." }
        require(rolloverCapPercent in 0..100) { "Rollover cap must be 0..100 percent." }
        require(bookingDiscountPercent in 0..100) { "Booking discount must be 0..100 percent." }
        maxMembers?.let { require(it > 0) { "maxMembers must be positive when set." } }
    }

    /** Largest number of unused minutes that may carry into the next period. */
    val rolloverCapMinutes: Int
        get() = (includedMinutesPerPeriod.toLong() * rolloverCapPercent / 100).toInt()

    /** Price expressed per month, useful for comparing plans and computing MRR. */
    val monthlyEquivalentCents: Long
        get() = Math.round(priceCents.toDouble() / interval.months)

    val isFree: Boolean get() = priceCents == 0L

    fun hasFeature(feature: String): Boolean = feature.trim().lowercase() in _features

    internal fun retire() {
        active = false
    }

    internal fun reactivate() {
        active = true
    }

    override fun toString(): String {
        val trial = if (trialDays > 0) " | ${trialDays}d trial" else ""
        val minutes = if (includedMinutesPerPeriod > 0) " | ${includedMinutesPerPeriod}min/period" else ""
        val discount = if (bookingDiscountPercent > 0) " | ${bookingDiscountPercent}% off" else ""
        val state = if (active) "" else " | RETIRED"
        return "[$id] $name | ${com.booking.util.Cents.format(priceCents)} ${interval.label.lowercase()}$trial$minutes$discount$state"
    }
}
