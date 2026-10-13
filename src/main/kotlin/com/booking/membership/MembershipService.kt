package com.booking.membership

import com.booking.service.AuditLog
import com.booking.util.Cents
import java.time.LocalDate

/**
 * Registry and lifecycle manager for membership plans, memberships and their
 * charges.
 *
 * Responsibilities:
 *
 *   * plan CRUD (plans are retired rather than deleted once anyone subscribed)
 *   * enrolment, including trials, setup fees and seat limits
 *   * lifecycle moves: pause/resume, cancel (immediate or at period end),
 *     plan change with proration, and scheduled downgrades
 *   * issuing [MembershipCharge]s and settling them through the gateway
 *
 * Time-driven work — renewals, trial conversion, dunning retries — lives in
 * [RenewalScheduler], which calls the internal hooks exposed here.
 */
class MembershipService(
    private val gateway: MembershipPaymentGateway,
    val benefits: BenefitLedger = BenefitLedger(),
    private val proration: ProrationCalculator = ProrationCalculator(),
    private val auditLog: AuditLog? = null,
) {
    private val plans = linkedMapOf<String, MembershipPlan>()
    private val memberships = linkedMapOf<String, Membership>()
    private val charges = linkedMapOf<String, MutableList<MembershipCharge>>()
    private val gatewayRefs = mutableMapOf<String, String>()

    // ── Plans ────────────────────────────────────────────────────────

    fun registerPlan(plan: MembershipPlan): MembershipPlan {
        require(plans.values.none { it.name.equals(plan.name, ignoreCase = true) && it.active }) {
            "An active plan named '${plan.name}' already exists."
        }
        plans[plan.id] = plan
        auditLog?.log(plan.id, AuditLog.Action.MEMBERSHIP_PLAN_REGISTERED, plan.toString())
        return plan
    }

    fun findPlan(id: String): MembershipPlan? = plans[id]

    fun requirePlan(id: String): MembershipPlan =
        plans[id] ?: throw IllegalArgumentException("No such plan: $id")

    fun findPlanByName(name: String): MembershipPlan? =
        plans.values.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) && it.active }

    fun listPlans(includeRetired: Boolean = false): List<MembershipPlan> =
        plans.values.filter { includeRetired || it.active }

    fun retirePlan(id: String) {
        val plan = requirePlan(id)
        plan.retire()
        auditLog?.log(plan.id, AuditLog.Action.MEMBERSHIP_PLAN_RETIRED, "Plan ${plan.name} retired")
    }

    fun reactivatePlan(id: String) {
        val plan = requirePlan(id)
        require(plans.values.none { it !== plan && it.active && it.name.equals(plan.name, ignoreCase = true) }) {
            "Another active plan is already called '${plan.name}'."
        }
        plan.reactivate()
    }

    /** Active, non-terminal subscribers on [planId]. */
    fun seatsUsed(planId: String): Int = memberships.values.count { it.planId == planId && it.isLive }

    fun seatsRemaining(planId: String): Int? =
        requirePlan(planId).maxMembers?.let { (it - seatsUsed(planId)).coerceAtLeast(0) }

    // ── Enrolment ────────────────────────────────────────────────────

    /**
     * Enrol [customerId] in [planId] starting on [start].
     *
     * With a trial the member begins `TRIALING`, owes nothing up front, and is
     * converted by the scheduler on the trial end date. Without a trial the
     * first period (plus any setup fee) is charged immediately; a decline on
     * that first charge aborts the enrolment entirely.
     */
    fun enroll(customerId: String, planId: String, start: LocalDate): Membership {
        require(customerId.isNotBlank()) { "Customer id cannot be blank." }
        val plan = requirePlan(planId)
        require(plan.active) { "Plan ${plan.name} is retired and cannot accept new members." }
        require(findLiveMembership(customerId, planId) == null) {
            "Customer $customerId already has a live ${plan.name} membership."
        }
        seatsRemaining(planId)?.let { require(it > 0) { "Plan ${plan.name} is full." } }

        val membership = Membership(customerId, planId, start)
        membership.currentPeriodStart = start

        if (plan.trialDays > 0) {
            val trialEnd = start.plusDays(plan.trialDays.toLong())
            membership.trialEnd = trialEnd
            membership.currentPeriodEnd = trialEnd
            membership.beginTrial("${plan.trialDays}-day trial")
            memberships[membership.id] = membership
            grantAllowance(membership, plan, start)
            if (plan.setupFeeCents > 0) {
                issueCharge(membership, MembershipCharge.Kind.SETUP, plan.setupFeeCents, null, null,
                    "Setup fee for ${plan.name}", start)
                settle(membership, openCharges(membership.id).last(), start)
            }
        } else {
            membership.currentPeriodEnd = plan.interval.nextPeriodStart(start, start)
            memberships[membership.id] = membership
            val firstTotal = plan.priceCents + plan.setupFeeCents
            if (firstTotal > 0) {
                val charge = issueCharge(membership, MembershipCharge.Kind.RECURRING, plan.priceCents,
                    membership.currentPeriodStart, membership.currentPeriodEnd, "${plan.name} first period", start)
                if (plan.setupFeeCents > 0) {
                    issueCharge(membership, MembershipCharge.Kind.SETUP, plan.setupFeeCents, null, null,
                        "Setup fee for ${plan.name}", start)
                }
                val outcome = settle(membership, charge, start)
                if (outcome is MembershipPaymentGateway.Result.Declined) {
                    memberships.remove(membership.id)
                    charges.remove(membership.id)
                    throw IllegalStateException("First payment declined: ${outcome.message}")
                }
                openCharges(membership.id).forEach { settle(membership, it, start) }
            }
            grantAllowance(membership, plan, start)
        }
        membership.record("ENROLLED", "plan ${plan.name} from $start")
        auditLog?.log(membership.id, AuditLog.Action.MEMBERSHIP_ENROLLED, "customer $customerId on ${plan.name} from $start")
        return membership
    }

    /**
     * Register a membership built outside the normal [enroll] path (CSV
     * migration). No gateway charge is made and no setup fee is applied.
     */
    internal fun registerImported(m: Membership) {
        require(m.id !in memberships) { "Membership ${m.id} already exists." }
        memberships[m.id] = m
        m.record("ENROLLED", "plan ${requirePlan(m.planId).name} from ${m.startDate} (imported)")
        auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_ENROLLED, "imported: customer ${m.customerId}")
    }

    fun find(id: String): Membership? = memberships[id]

    fun require(id: String): Membership =
        memberships[id] ?: throw IllegalArgumentException("No such membership: $id")

    fun all(): List<Membership> = memberships.values.toList()

    fun forCustomer(customerId: String): List<Membership> =
        memberships.values.filter { it.customerId == customerId }

    fun findLiveMembership(customerId: String, planId: String): Membership? =
        memberships.values.firstOrNull { it.customerId == customerId && it.planId == planId && it.isLive }

    fun byStatus(status: Membership.Status): List<Membership> =
        memberships.values.filter { it.status == status }

    // ── Lifecycle ────────────────────────────────────────────────────

    fun pause(id: String, until: LocalDate?, today: LocalDate) {
        val m = require(id)
        require(m.status == Membership.Status.ACTIVE) { "Only ACTIVE memberships can be paused (is ${m.status})." }
        until?.let { require(it.isAfter(today)) { "Resume date must be in the future." } }
        m.pausedUntil = until
        m.pausedOn = today
        m.transitionTo(Membership.Status.PAUSED, until?.let { "until $it" } ?: "indefinitely")
        auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_PAUSED, until?.let { "until $it" } ?: "indefinitely")
    }

    /**
     * Resume a paused membership. The paused interval is *not* billed: the
     * period boundaries shift forward by the days spent paused so the member
     * gets the time they already paid for.
     */
    fun resume(id: String, today: LocalDate) {
        val m = require(id)
        require(m.status == Membership.Status.PAUSED) { "Membership is not paused (is ${m.status})." }
        val pausedOn = m.pausedOn ?: today
        val daysPaused = (today.toEpochDay() - pausedOn.toEpochDay()).coerceAtLeast(0)
        m.pausedOn = null
        m.currentPeriodEnd = m.currentPeriodEnd.plusDays(daysPaused)
        m.pausedUntil = null
        m.transitionTo(Membership.Status.ACTIVE, "resumed after $daysPaused paused days")
        auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_RESUMED, "period extended by $daysPaused days")
    }

    /**
     * Cancel a membership.
     *
     * With [atPeriodEnd] the member keeps benefits until the period ends and
     * the scheduler finalises the cancellation. An immediate cancel ends it now
     * and, when [refundUnused] is set, refunds the unused fraction of the
     * period through the gateway.
     */
    fun cancel(id: String, today: LocalDate, atPeriodEnd: Boolean, reason: String, refundUnused: Boolean = false): Long {
        val m = require(id)
        require(m.isLive) { "Membership ${m.id} is already ${m.status}." }
        m.cancelReason = reason.ifBlank { "unspecified" }
        if (atPeriodEnd) {
            m.cancelAtPeriodEnd = true
            m.record("CANCEL_SCHEDULED", "ends ${m.currentPeriodEnd}: ${m.cancelReason}")
            auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_CANCEL_SCHEDULED, "ends ${m.currentPeriodEnd}: ${m.cancelReason}")
            return 0
        }
        var refunded = 0L
        if (refundUnused && m.hasBenefits) {
            val plan = requirePlan(m.planId)
            val amount = proration.refundForImmediateCancel(plan, m.currentPeriodStart, m.currentPeriodEnd, today)
            if (amount > 0) refunded = refundAgainstPaid(m, amount, today, "Unused time refund on cancellation")
        }
        finalizeCancellation(m, today)
        auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_CANCELLED, "${m.cancelReason}; refunded ${Cents.format(refunded)}")
        return refunded
    }

    fun undoScheduledCancel(id: String) {
        val m = require(id)
        require(m.cancelAtPeriodEnd) { "No scheduled cancellation on ${m.id}." }
        require(m.isLive) { "Membership ${m.id} is already ${m.status}." }
        m.cancelAtPeriodEnd = false
        m.cancelReason = null
        m.record("CANCEL_UNDONE", "scheduled cancellation withdrawn")
    }

    /**
     * Change plan immediately, prorating against the current period. Returns
     * the proration result; the net amount is charged (or credited) at once.
     */
    fun changePlan(id: String, newPlanId: String, today: LocalDate): ProrationCalculator.Result {
        val m = require(id)
        require(m.status == Membership.Status.ACTIVE) { "Only ACTIVE memberships can change plan (is ${m.status})." }
        require(newPlanId != m.planId) { "Membership is already on that plan." }
        val oldPlan = requirePlan(m.planId)
        val newPlan = requirePlan(newPlanId)
        require(newPlan.active) { "Plan ${newPlan.name} is retired." }
        seatsRemaining(newPlanId)?.let { require(it > 0) { "Plan ${newPlan.name} is full." } }

        val result = proration.prorate(oldPlan, newPlan, m.currentPeriodStart, m.currentPeriodEnd, today)
        if (result.netCents > 0) {
            val charge = issueCharge(m, MembershipCharge.Kind.PRORATION, result.netCents, today, m.currentPeriodEnd,
                "Plan change ${oldPlan.name} -> ${newPlan.name}", today)
            val outcome = settle(m, charge, today)
            if (outcome is MembershipPaymentGateway.Result.Declined) {
                charge.void("plan change declined")
                throw IllegalStateException("Plan change declined: ${outcome.message}")
            }
        } else if (result.netCents < 0) {
            issueCharge(m, MembershipCharge.Kind.CREDIT, result.netCents, today, m.currentPeriodEnd,
                "Plan change credit ${oldPlan.name} -> ${newPlan.name}", today)
        }

        m.planId = newPlanId
        m.pendingPlanId = null
        adjustAllowanceForPlanChange(m, oldPlan, newPlan, today)
        m.record("PLAN_CHANGED", "${oldPlan.name} -> ${newPlan.name}, net ${Cents.format(result.netCents)}")
        auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_PLAN_CHANGED,
            "${oldPlan.name} -> ${newPlan.name}, net ${Cents.format(result.netCents)}")
        return result
    }

    /** Schedule a plan change to take effect at the next renewal, with no proration. */
    fun scheduleDowngrade(id: String, newPlanId: String) {
        val m = require(id)
        require(m.isLive) { "Membership ${m.id} is ${m.status}." }
        require(newPlanId != m.planId) { "Membership is already on that plan." }
        val plan = requirePlan(newPlanId)
        require(plan.active) { "Plan ${plan.name} is retired." }
        m.pendingPlanId = newPlanId
        m.record("DOWNGRADE_SCHEDULED", "to ${plan.name} at ${m.currentPeriodEnd}")
    }

    // ── Benefit helpers ──────────────────────────────────────────────

    /** The discount percentage [customerId] currently earns on bookings (best across live memberships). */
    fun discountFor(customerId: String): Int =
        forCustomer(customerId)
            .filter { it.hasBenefits }
            .maxOfOrNull { requirePlan(it.planId).bookingDiscountPercent } ?: 0

    /** Total benefit-minute balance across a customer's memberships that currently hold benefits. */
    fun minutesAvailable(customerId: String): Int =
        forCustomer(customerId).filter { it.hasBenefits }.sumOf { benefits.balance(it.id) }

    /**
     * Spend benefit minutes for a booking, drawing from the membership with
     * the soonest period end first. Returns the (membership id, minutes) draws.
     */
    fun consumeMinutes(customerId: String, minutes: Int, on: LocalDate, bookingId: String): List<Pair<String, Int>> {
        require(minutes > 0) { "Minutes must be positive." }
        val candidates = forCustomer(customerId)
            .filter { it.hasBenefits && benefits.balance(it.id) > 0 }
            .sortedBy { it.currentPeriodEnd }
        val total = candidates.sumOf { benefits.balance(it.id) }
        require(total >= minutes) { "Insufficient benefit minutes: need $minutes, have $total." }
        var remaining = minutes
        val draws = mutableListOf<Pair<String, Int>>()
        for (m in candidates) {
            if (remaining == 0) break
            val take = minOf(remaining, benefits.balance(m.id))
            benefits.consume(m.id, take, on, "$bookingId#${m.id}")
            draws += m.id to take
            remaining -= take
        }
        return draws
    }

    /** Return the minutes drawn by [bookingId] to every membership that funded it. */
    fun refundMinutes(customerId: String, bookingId: String, on: LocalDate): Int {
        var total = 0
        for (m in forCustomer(customerId)) {
            benefits.refundBooking(m.id, "$bookingId#${m.id}", on)?.let { total += it.minutes }
        }
        return total
    }

    // ── Charges ──────────────────────────────────────────────────────

    fun chargesFor(membershipId: String): List<MembershipCharge> = charges[membershipId]?.toList() ?: emptyList()

    fun allCharges(): List<MembershipCharge> = charges.values.flatten()

    fun openCharges(membershipId: String): List<MembershipCharge> =
        chargesFor(membershipId).filter { it.isOpen }

    fun outstandingCents(membershipId: String): Long =
        openCharges(membershipId).sumOf { it.amountCents }

    /** Net cash actually collected: paid charges (including negative credits). */
    fun netCollectedCents(): Long =
        allCharges().filter { it.status == MembershipCharge.Status.PAID }.sumOf { it.amountCents }

    internal fun issueCharge(
        m: Membership,
        kind: MembershipCharge.Kind,
        amountCents: Long,
        periodStart: LocalDate?,
        periodEnd: LocalDate?,
        description: String,
        due: LocalDate,
    ): MembershipCharge {
        val charge = MembershipCharge(m.id, kind, amountCents, periodStart, periodEnd, description, due)
        charges.getOrPut(m.id) { mutableListOf() }.add(charge)
        return charge
    }

    /**
     * Run [charge] through the gateway and record the attempt. Charges with a
     * non-positive amount are already PAID and are returned as approved.
     */
    internal fun settle(m: Membership, charge: MembershipCharge, today: LocalDate): MembershipPaymentGateway.Result {
        if (charge.amountCents <= 0) return MembershipPaymentGateway.Result.Approved("no-charge")
        val result = gateway.charge(m.customerId, charge.amountCents, charge.idempotencyKey)
        when (result) {
            is MembershipPaymentGateway.Result.Approved -> {
                charge.recordAttempt(true, "approved ${result.reference}", today)
                gatewayRefs[charge.id] = result.reference
                m.record("PAYMENT_OK", "${Cents.format(charge.amountCents)} ${charge.kind}")
                auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_PAYMENT_OK, "${Cents.format(charge.amountCents)} ${charge.kind}")
            }
            is MembershipPaymentGateway.Result.Declined -> {
                charge.recordAttempt(false, result.message, today)
                m.record("PAYMENT_FAILED", "${Cents.format(charge.amountCents)} ${charge.kind}: ${result.reason}")
                auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_PAYMENT_FAILED,
                    "${Cents.format(charge.amountCents)} ${charge.kind}: ${result.reason}")
            }
        }
        return result
    }

    internal fun grantAllowance(m: Membership, plan: MembershipPlan, on: LocalDate) {
        if (plan.includedMinutesPerPeriod > 0) {
            benefits.grant(m.id, plan.includedMinutesPerPeriod, on, "${plan.name} allowance")
        }
    }

    internal fun finalizeCancellation(m: Membership, on: LocalDate) {
        m.transitionTo(Membership.Status.CANCELLED, m.cancelReason ?: "")
        m.endedOn = on
        m.cancelAtPeriodEnd = false
        for (c in openCharges(m.id)) c.void("membership cancelled")
        val leftover = benefits.balance(m.id)
        if (leftover > 0) benefits.closePeriod(m.id, on, 0)
    }

    internal fun gatewayReference(chargeId: String): String? = gatewayRefs[chargeId]

    // ── Internals ────────────────────────────────────────────────────

    private fun refundAgainstPaid(m: Membership, amount: Long, today: LocalDate, description: String): Long {
        val source = chargesFor(m.id)
            .lastOrNull { it.status == MembershipCharge.Status.PAID && it.kind == MembershipCharge.Kind.RECURRING }
        val ref = source?.let { gatewayRefs[it.id] }
        if (source == null || ref == null) return 0
        val capped = minOf(amount, source.amountCents)
        val result = gateway.refund(m.customerId, capped, ref)
        if (result is MembershipPaymentGateway.Result.Approved) {
            val credit = issueCharge(m, MembershipCharge.Kind.CREDIT, -capped, source.periodStart, source.periodEnd,
                description, today)
            credit.noteSettled("refund ${result.reference}", today)
            auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_REFUNDED, "${Cents.format(capped)} $description")
            return capped
        }
        return 0
    }

    private fun adjustAllowanceForPlanChange(m: Membership, oldPlan: MembershipPlan, newPlan: MembershipPlan, on: LocalDate) {
        val delta = newPlan.includedMinutesPerPeriod - oldPlan.includedMinutesPerPeriod
        if (delta > 0) {
            val periodLength = m.currentPeriodEnd.toEpochDay() - m.currentPeriodStart.toEpochDay()
            val remaining = m.currentPeriodEnd.toEpochDay() - on.toEpochDay()
            val bonus = Cents.ratio(delta.toLong(), remaining, periodLength).toInt()
            if (bonus > 0) benefits.adjust(m.id, bonus, on, "Upgrade top-up to ${newPlan.name}")
        }
    }
}
