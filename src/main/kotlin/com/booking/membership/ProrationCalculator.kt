package com.booking.membership

import com.booking.util.Cents
import java.time.LocalDate

/**
 * Computes the money movement when a member changes plan part-way through a
 * billing period.
 *
 * The model is the standard "unused-time credit": the old plan's price is
 * credited for the days not yet consumed, and the new plan's price is charged
 * for the same remaining days. Both amounts use the real length of the current
 * period (28–31 days for monthly, 365/366 for annual), so a change on day 10
 * of a 30-day period credits exactly 20/30 of the old price.
 */
class ProrationCalculator {

    /** A single priced line on a proration. */
    data class Line(
        val description: String,
        val amountCents: Long,
    ) {
        override fun toString(): String = "${description.padEnd(46)} ${Cents.format(amountCents).padStart(10)}"
    }

    /**
     * The outcome of a plan change. [netCents] > 0 means the member owes money;
     * < 0 means they are owed a credit.
     */
    data class Result(
        val effectiveDate: LocalDate,
        val daysRemaining: Long,
        val daysInPeriod: Long,
        val lines: List<Line>,
    ) {
        val netCents: Long get() = lines.sumOf { it.amountCents }
        val isCredit: Boolean get() = netCents < 0
        val isNoOp: Boolean get() = lines.all { it.amountCents == 0L }

        fun render(): String = buildString {
            appendLine("Proration effective $effectiveDate ($daysRemaining of $daysInPeriod days remaining)")
            lines.forEach { appendLine("  $it") }
            append("  ${"Net".padEnd(46)} ${Cents.format(netCents).padStart(10)}")
        }
    }

    /**
     * Prorate a switch from [from] to [to] on [effective] within the period
     * `[periodStart, periodEnd)`.
     *
     * @throws IllegalArgumentException when [effective] falls outside the period
     */
    fun prorate(
        from: MembershipPlan,
        to: MembershipPlan,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        effective: LocalDate,
    ): Result {
        require(periodEnd.isAfter(periodStart)) { "Period must end after it starts." }
        require(!effective.isBefore(periodStart) && effective.isBefore(periodEnd)) {
            "Effective date $effective is outside the period $periodStart..$periodEnd."
        }
        val daysInPeriod = periodEnd.toEpochDay() - periodStart.toEpochDay()
        val daysRemaining = periodEnd.toEpochDay() - effective.toEpochDay()

        val credit = -Cents.ratio(from.priceCents, daysRemaining, daysInPeriod)
        val charge = Cents.ratio(to.priceCents, daysRemaining, daysInPeriod)

        val lines = mutableListOf<Line>()
        lines += Line("Unused time on ${from.name} ($daysRemaining/$daysInPeriod d)", credit)
        lines += Line("Remaining time on ${to.name} ($daysRemaining/$daysInPeriod d)", charge)
        if (from.interval != to.interval && to.setupFeeCents > 0) {
            lines += Line("Setup fee for ${to.name}", to.setupFeeCents)
        }
        return Result(effective, daysRemaining, daysInPeriod, lines)
    }

    /**
     * What a member would be refunded for cancelling immediately: the unused
     * fraction of their current plan. Never negative.
     */
    fun refundForImmediateCancel(
        plan: MembershipPlan,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        effective: LocalDate,
    ): Long {
        if (!effective.isBefore(periodEnd)) return 0
        val clamped = if (effective.isBefore(periodStart)) periodStart else effective
        val total = periodEnd.toEpochDay() - periodStart.toEpochDay()
        val remaining = periodEnd.toEpochDay() - clamped.toEpochDay()
        return Cents.ratio(plan.priceCents, remaining, total)
    }

    /**
     * Prorated charge for joining mid-cycle when the plan bills on a calendar
     * [anchorDay] rather than the join date. Returns the cents owed up to the
     * first anchor date after [joinDate].
     */
    fun joinToAnchor(plan: MembershipPlan, joinDate: LocalDate, anchorDay: Int): Pair<LocalDate, Long> {
        require(anchorDay in 1..28) { "Anchor day must be 1..28 so every month has one." }
        var anchor = joinDate.withDayOfMonth(anchorDay)
        if (!anchor.isAfter(joinDate)) anchor = anchor.plusMonths(1)
        val previous = anchor.minusMonths(1)
        val total = anchor.toEpochDay() - previous.toEpochDay()
        val remaining = anchor.toEpochDay() - joinDate.toEpochDay()
        val monthlyShare = Cents.ratio(plan.monthlyEquivalentCents, remaining, total)
        return anchor to monthlyShare
    }
}
