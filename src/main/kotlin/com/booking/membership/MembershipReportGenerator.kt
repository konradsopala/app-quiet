package com.booking.membership

import com.booking.util.Cents
import com.booking.util.TextTable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Read-only analytics over the membership book: recurring revenue, churn,
 * retention cohorts, arrears ageing and benefit liability.
 *
 * Every figure is derived from the current in-memory state of
 * [MembershipService]; nothing is cached, so a report always reflects the
 * latest lifecycle changes.
 */
class MembershipReportGenerator(private val service: MembershipService) {

    // ── Value types ──────────────────────────────────────────────────

    data class PlanRow(
        val plan: MembershipPlan,
        val live: Int,
        val trialing: Int,
        val pastDue: Int,
        val paused: Int,
        val mrrCents: Long,
    )

    data class ChurnSummary(
        val from: LocalDate,
        val to: LocalDate,
        val startingMembers: Int,
        val churned: Int,
        val joined: Int,
    ) {
        /** Fraction of the starting cohort lost in the window, 0.0 when nobody started. */
        val churnRate: Double get() = if (startingMembers == 0) 0.0 else churned.toDouble() / startingMembers
        val netGrowth: Int get() = joined - churned
    }

    data class CohortRow(
        val label: String,
        val size: Int,
        val stillLive: Int,
    ) {
        val retention: Double get() = if (size == 0) 0.0 else stillLive.toDouble() / size
    }

    data class ArrearsBucket(val label: String, val count: Int, val cents: Long)

    // ── Revenue ──────────────────────────────────────────────────────

    /**
     * Monthly recurring revenue: every member currently paying (ACTIVE or
     * PAST_DUE) contributes their plan's monthly-equivalent price. Trials,
     * paused and suspended members contribute nothing.
     */
    fun monthlyRecurringRevenueCents(): Long =
        service.all()
            .filter { it.status == Membership.Status.ACTIVE || it.status == Membership.Status.PAST_DUE }
            .sumOf { service.requirePlan(it.planId).monthlyEquivalentCents }

    fun annualRunRateCents(): Long = monthlyRecurringRevenueCents() * 12

    /** Average revenue per paying member, in cents; 0 when there are none. */
    fun arpuCents(): Long {
        val payers = service.all().count { it.status == Membership.Status.ACTIVE || it.status == Membership.Status.PAST_DUE }
        return if (payers == 0) 0 else monthlyRecurringRevenueCents() / payers
    }

    fun planBreakdown(): List<PlanRow> =
        service.listPlans(includeRetired = true).map { plan ->
            val members = service.all().filter { it.planId == plan.id }
            val paying = members.filter { it.status == Membership.Status.ACTIVE || it.status == Membership.Status.PAST_DUE }
            PlanRow(
                plan = plan,
                live = members.count { it.isLive },
                trialing = members.count { it.status == Membership.Status.TRIALING },
                pastDue = members.count { it.status == Membership.Status.PAST_DUE },
                paused = members.count { it.status == Membership.Status.PAUSED },
                mrrCents = paying.size * plan.monthlyEquivalentCents,
            )
        }.sortedByDescending { it.mrrCents }

    // ── Churn & cohorts ──────────────────────────────────────────────

    /**
     * Churn over `[from, to]`. A member counts as part of the starting cohort
     * if they had started before [from] and had not already ended; as churned
     * if their `endedOn` falls inside the window.
     */
    fun churn(from: LocalDate, to: LocalDate): ChurnSummary {
        require(!to.isBefore(from)) { "Window end must not precede its start." }
        val all = service.all()
        val starting = all.filter { it.startDate.isBefore(from) && (it.endedOn == null || !it.endedOn!!.isBefore(from)) }
        val churned = all.filter { m ->
            val ended = m.endedOn
            ended != null && !ended.isBefore(from) && !ended.isAfter(to) && !m.startDate.isAfter(to)
        }
        val joined = all.filter { !it.startDate.isBefore(from) && !it.startDate.isAfter(to) }
        val startedAndChurned = starting.count { s -> churned.any { it.id == s.id } }
        return ChurnSummary(from, to, starting.size, startedAndChurned, joined.size)
    }

    /** Retention by signup month: how many of each month's joiners are still live. */
    fun cohorts(): List<CohortRow> =
        service.all()
            .groupBy { "%04d-%02d".format(it.startDate.year, it.startDate.monthValue) }
            .toSortedMap()
            .map { (label, members) -> CohortRow(label, members.size, members.count { it.isLive }) }

    /** Mean tenure in days across all memberships, using [asOf] for those still live. */
    fun averageTenureDays(asOf: LocalDate): Double {
        val all = service.all()
        if (all.isEmpty()) return 0.0
        return all.map { ChronoUnit.DAYS.between(it.startDate, it.endedOn ?: asOf).coerceAtLeast(0) }.average()
    }

    /** Why members left, most common first. */
    fun cancelReasons(): List<Pair<String, Int>> =
        service.all()
            .filter { it.status == Membership.Status.CANCELLED || it.status == Membership.Status.EXPIRED }
            .groupingBy { it.cancelReason ?: if (it.status == Membership.Status.EXPIRED) "non-payment" else "unspecified" }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    // ── Arrears ──────────────────────────────────────────────────────

    /** Open charges grouped into ageing buckets by days overdue as of [asOf]. */
    fun arrearsAgeing(asOf: LocalDate): List<ArrearsBucket> {
        val open = service.allCharges().filter { it.isOpen }
        fun bucket(label: String, range: LongRange): ArrearsBucket {
            val inRange = open.filter { it.daysOverdue(asOf) in range }
            return ArrearsBucket(label, inRange.size, inRange.sumOf { it.amountCents })
        }
        return listOf(
            bucket("Not yet due", Long.MIN_VALUE..0L),
            bucket("1-7 days", 1L..7L),
            bucket("8-30 days", 8L..30L),
            bucket("31+ days", 31L..Long.MAX_VALUE),
        )
    }

    fun totalArrearsCents(): Long = service.allCharges().filter { it.isOpen }.sumOf { it.amountCents }

    // ── Upcoming & liability ─────────────────────────────────────────

    /** Memberships that will renew (and be charged) within the next [days] days. */
    fun upcomingRenewals(asOf: LocalDate, days: Int): List<Membership> {
        require(days > 0) { "Look-ahead must be positive." }
        val horizon = asOf.plusDays(days.toLong())
        return service.all()
            .filter { (it.status == Membership.Status.ACTIVE || it.status == Membership.Status.TRIALING) && !it.cancelAtPeriodEnd }
            .filter { !it.currentPeriodEnd.isBefore(asOf) && !it.currentPeriodEnd.isAfter(horizon) }
            .sortedBy { it.currentPeriodEnd }
    }

    /** Revenue about to be billed within the look-ahead, using each member's (possibly pending) plan. */
    fun upcomingRenewalRevenueCents(asOf: LocalDate, days: Int): Long =
        upcomingRenewals(asOf, days).sumOf { service.requirePlan(it.pendingPlanId ?: it.planId).priceCents }

    /** Members whose scheduled cancellation takes effect within [days] days. */
    fun upcomingCancellations(asOf: LocalDate, days: Int): List<Membership> {
        val horizon = asOf.plusDays(days.toLong())
        return service.all()
            .filter { it.isLive && it.cancelAtPeriodEnd && !it.currentPeriodEnd.isAfter(horizon) }
            .sortedBy { it.currentPeriodEnd }
    }

    // ── Rendering ────────────────────────────────────────────────────

    fun renderSummary(asOf: LocalDate): String = buildString {
        val all = service.all()
        appendLine("=== Membership Summary (as of $asOf) ===")
        appendLine("Plans:            ${service.listPlans().size} active, ${service.listPlans(true).size - service.listPlans().size} retired")
        appendLine("Members:          ${all.size} total, ${all.count { it.isLive }} live")
        Membership.Status.values().forEach { st ->
            val n = all.count { it.status == st }
            if (n > 0) appendLine("  ${st.name.padEnd(10)} $n")
        }
        appendLine("MRR:              ${Cents.format(monthlyRecurringRevenueCents())}")
        appendLine("Annual run rate:  ${Cents.format(annualRunRateCents())}")
        appendLine("ARPU:             ${Cents.format(arpuCents())}")
        appendLine("Arrears:          ${Cents.format(totalArrearsCents())}")
        appendLine("Net collected:    ${Cents.format(service.netCollectedCents())}")
        appendLine("Benefit minutes:  ${service.benefits.outstandingLiability()} outstanding")
        append("Avg tenure:       %.1f days".format(averageTenureDays(asOf)))
    }

    fun renderPlanBreakdown(): String {
        val rows = planBreakdown()
        if (rows.isEmpty()) return "No plans registered."
        val table = TextTable(listOf("Plan", "Interval", "Price", "Live", "Trial", "PastDue", "Paused", "MRR"))
        for (i in 2..7) table.align(i, TextTable.Align.RIGHT)
        for (r in rows) {
            table.row(
                r.plan.name + if (r.plan.active) "" else " (retired)",
                r.plan.interval.label,
                Cents.format(r.plan.priceCents),
                r.live.toString(),
                r.trialing.toString(),
                r.pastDue.toString(),
                r.paused.toString(),
                Cents.format(r.mrrCents),
            )
        }
        return table.render()
    }

    fun renderChurn(summary: ChurnSummary): String = buildString {
        appendLine("Churn ${summary.from} .. ${summary.to}")
        appendLine("  Starting members: ${summary.startingMembers}")
        appendLine("  Joined:           ${summary.joined}")
        appendLine("  Churned:          ${summary.churned}")
        appendLine("  Churn rate:       %.1f%%".format(summary.churnRate * 100))
        append("  Net growth:       ${summary.netGrowth}")
    }

    fun renderCohorts(): String {
        val rows = cohorts()
        if (rows.isEmpty()) return "No memberships yet."
        val table = TextTable(listOf("Cohort", "Joined", "Still live", "Retention", ""))
        for (i in 1..3) table.align(i, TextTable.Align.RIGHT)
        for (r in rows) {
            val bar = "#".repeat((r.retention * 20).toInt())
            table.row(r.label, r.size.toString(), r.stillLive.toString(), "%.0f%%".format(r.retention * 100), bar)
        }
        return table.render()
    }

    fun renderArrears(asOf: LocalDate): String {
        val table = TextTable(listOf("Ageing", "Charges", "Amount")).align(1, TextTable.Align.RIGHT).align(2, TextTable.Align.RIGHT)
        arrearsAgeing(asOf).forEach { table.row(it.label, it.count.toString(), Cents.format(it.cents)) }
        return table.render()
    }

    fun renderUpcoming(asOf: LocalDate, days: Int): String {
        val upcoming = upcomingRenewals(asOf, days)
        if (upcoming.isEmpty()) return "No renewals in the next $days days."
        val table = TextTable(listOf("Renews", "Membership", "Customer", "Plan", "Amount"))
        table.align(4, TextTable.Align.RIGHT)
        for (m in upcoming) {
            val plan = service.requirePlan(m.pendingPlanId ?: m.planId)
            val suffix = if (m.pendingPlanId != null) " (change)" else ""
            table.row(m.currentPeriodEnd.toString(), m.id, m.customerId, plan.name + suffix, Cents.format(plan.priceCents))
        }
        return table.render() + "\nTotal due: ${Cents.format(upcomingRenewalRevenueCents(asOf, days))}"
    }

    fun renderCancelReasons(): String {
        val reasons = cancelReasons()
        if (reasons.isEmpty()) return "No cancellations recorded."
        val table = TextTable(listOf("Reason", "Count")).align(1, TextTable.Align.RIGHT)
        reasons.forEach { (reason, n) -> table.row(reason, n.toString()) }
        return table.render()
    }

    fun renderMembershipDetail(id: String, asOf: LocalDate): String {
        val m = service.require(id)
        val plan = service.requirePlan(m.planId)
        return buildString {
            appendLine("Membership ${m.id}")
            appendLine("  Customer:   ${m.customerId}")
            appendLine("  Plan:       ${plan.name} (${Cents.format(plan.priceCents)} ${plan.interval.label.lowercase()})")
            appendLine("  Status:     ${m.status}")
            appendLine("  Period:     ${m.currentPeriodStart} .. ${m.currentPeriodEnd} (${m.daysRemainingInPeriod(asOf)} days left)")
            m.trialEnd?.let { appendLine("  Trial ends: $it") }
            m.pausedUntil?.let { appendLine("  Resumes:    $it") }
            m.pendingPlanId?.let { appendLine("  Next plan:  ${service.requirePlan(it).name}") }
            if (m.cancelAtPeriodEnd) appendLine("  Cancels:    at period end (${m.cancelReason})")
            appendLine("  Minutes:    ${service.benefits.balance(m.id)} available, ${service.benefits.totalConsumed(m.id)} used")
            appendLine("  Owed:       ${Cents.format(service.outstandingCents(m.id))}")
            appendLine("  History:")
            val events = m.events
            if (events.isEmpty()) append("    (none)") else events.takeLast(12).forEachIndexed { i, e ->
                append("    $e")
                if (i != events.takeLast(12).lastIndex) appendLine()
            }
        }
    }
}
