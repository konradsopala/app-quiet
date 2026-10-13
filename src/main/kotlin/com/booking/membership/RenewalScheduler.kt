package com.booking.membership

import com.booking.service.AuditLog
import com.booking.util.Cents
import java.time.LocalDate

/**
 * Drives every time-based membership transition. Call [runDue] once per day
 * (or with a simulated date in the CLI); it is idempotent for a given date, so
 * running it twice changes nothing the second time.
 *
 * Per membership, in this order:
 *
 *   1. auto-resume paused members whose `pausedUntil` has arrived
 *   2. retry any failed charge that is due under the [DunningPolicy]
 *   3. expire `SUSPENDED` members past the grace window
 *   4. convert trials and renew periods that have ended
 */
class RenewalScheduler(
    private val service: MembershipService,
    private val policy: DunningPolicy = DunningPolicy.DEFAULT,
    private val auditLog: AuditLog? = null,
) {

    /** What happened to one membership during a run. */
    data class Outcome(
        val membershipId: String,
        val action: Action,
        val detail: String,
    ) {
        override fun toString(): String = "${membershipId.padEnd(22)} ${action.name.padEnd(16)} $detail"
    }

    enum class Action {
        RESUMED, TRIAL_CONVERTED, TRIAL_FAILED, RENEWED, RENEWAL_FAILED, RETRY_OK, RETRY_FAILED,
        CANCELLED, SUSPENDED, EXPIRED, DOWNGRADED,
    }

    data class Report(val asOf: LocalDate, val outcomes: List<Outcome>) {
        fun count(action: Action): Int = outcomes.count { it.action == action }
        val isEmpty: Boolean get() = outcomes.isEmpty()

        fun render(): String = buildString {
            appendLine("Renewal run for $asOf: ${outcomes.size} action(s)")
            if (outcomes.isEmpty()) append("  (nothing due)")
            outcomes.forEachIndexed { i, o ->
                append("  $o")
                if (i != outcomes.lastIndex) appendLine()
            }
        }
    }

    fun runDue(asOf: LocalDate): Report {
        val outcomes = mutableListOf<Outcome>()
        for (m in service.all()) {
            if (!m.isLive) continue
            autoResume(m, asOf)?.let { outcomes += it }
            outcomes += retryDue(m, asOf)
            expireIfStale(m, asOf)?.let { outcomes += it }
            if (m.isLive) outcomes += rollPeriods(m, asOf)
        }
        return Report(asOf, outcomes)
    }

    // ── Step 1: resume ───────────────────────────────────────────────

    private fun autoResume(m: Membership, asOf: LocalDate): Outcome? {
        if (m.status != Membership.Status.PAUSED) return null
        val until = m.pausedUntil ?: return null
        if (until.isAfter(asOf)) return null
        service.resume(m.id, asOf)
        return Outcome(m.id, Action.RESUMED, "auto-resume on $until")
    }

    // ── Step 2: dunning ──────────────────────────────────────────────

    private fun retryDue(m: Membership, asOf: LocalDate): List<Outcome> {
        if (m.status != Membership.Status.PAST_DUE) return emptyList()
        val out = mutableListOf<Outcome>()
        for (charge in service.chargesFor(m.id).filter { it.status == MembershipCharge.Status.FAILED }) {
            val attempt = charge.attempts.count { !it.message.startsWith("voided") } + 1
            val offset = policy.offsetForAttempt(attempt)
            if (offset == null) {
                out += suspend(m, charge, asOf, "retries exhausted")
                continue
            }
            val dueOn = charge.dueDate.plusDays(offset.toLong())
            if (dueOn.isAfter(asOf)) continue

            when (val result = service.settle(m, charge, asOf)) {
                is MembershipPaymentGateway.Result.Approved -> {
                    out += Outcome(m.id, Action.RETRY_OK, "${Cents.format(charge.amountCents)} collected on attempt $attempt")
                    restoreIfCleared(m)
                }
                is MembershipPaymentGateway.Result.Declined -> {
                    m.failedAttempts += 1
                    val finalAttempt = policy.isExhausted(attempt)
                    if (finalAttempt || (!result.reason.retryable && policy.stopOnNonRetryable)) {
                        out += Outcome(m.id, Action.RETRY_FAILED, "attempt $attempt declined (${result.reason})")
                        out += suspend(m, charge, asOf, if (finalAttempt) "retries exhausted" else "non-retryable: ${result.reason}")
                    } else {
                        out += Outcome(m.id, Action.RETRY_FAILED, "attempt $attempt declined (${result.reason})")
                    }
                }
            }
        }
        return out
    }

    private fun restoreIfCleared(m: Membership) {
        if (m.status == Membership.Status.PAST_DUE && service.openCharges(m.id).isEmpty()) {
            m.failedAttempts = 0
            m.transitionTo(Membership.Status.ACTIVE, "arrears cleared")
        }
    }

    private fun suspend(m: Membership, charge: MembershipCharge, asOf: LocalDate, why: String): Outcome {
        if (m.status == Membership.Status.PAST_DUE) {
            m.transitionTo(Membership.Status.SUSPENDED, why)
            m.suspendedOn = asOf
            auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_SUSPENDED, "$why (charge ${charge.id})")
        }
        return Outcome(m.id, Action.SUSPENDED, "$why; ${Cents.format(charge.amountCents)} outstanding")
    }

    // ── Step 3: expire ───────────────────────────────────────────────

    private fun expireIfStale(m: Membership, asOf: LocalDate): Outcome? {
        if (m.status != Membership.Status.SUSPENDED) return null
        val suspendedOn = m.suspendedOn ?: return null
        val deadline = suspendedOn.plusDays(policy.suspendedGraceDays.toLong())
        if (asOf.isBefore(deadline)) return null
        service.openCharges(m.id).forEach { it.void("membership expired") }
        m.transitionTo(Membership.Status.EXPIRED, "grace period ended $deadline")
        m.endedOn = asOf
        service.benefits.closePeriod(m.id, asOf, 0)
        auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_EXPIRED, "grace ended $deadline")
        return Outcome(m.id, Action.EXPIRED, "grace period ended $deadline")
    }

    // ── Step 4: roll periods ─────────────────────────────────────────

    private fun rollPeriods(m: Membership, asOf: LocalDate): List<Outcome> {
        val out = mutableListOf<Outcome>()
        // A long-idle membership may have crossed several boundaries; process
        // each in order, but stop as soon as one fails so we never bill past arrears.
        var guard = 0
        while (m.isLive && !m.currentPeriodEnd.isAfter(asOf) && guard++ < MAX_PERIODS_PER_RUN) {
            val boundary = m.currentPeriodEnd
            if (m.status == Membership.Status.PAUSED || m.status == Membership.Status.SUSPENDED) break
            if (m.status == Membership.Status.PAST_DUE) break

            if (m.cancelAtPeriodEnd) {
                service.finalizeCancellation(m, boundary)
                auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_CANCELLED, "period ended $boundary: ${m.cancelReason}")
                out += Outcome(m.id, Action.CANCELLED, "period ended $boundary")
                break
            }
            out += if (m.status == Membership.Status.TRIALING) convertTrial(m, boundary) else renew(m, boundary)
            if (m.status != Membership.Status.ACTIVE) break
        }
        return out
    }

    private fun convertTrial(m: Membership, boundary: LocalDate): List<Outcome> {
        val plan = service.requirePlan(m.planId)
        val end = plan.interval.nextPeriodStart(boundary, boundary)
        val charge = service.issueCharge(m, MembershipCharge.Kind.RECURRING, plan.priceCents, boundary, end,
            "${plan.name} first paid period", boundary)
        m.trialEnd = null
        m.currentPeriodStart = boundary
        m.currentPeriodEnd = end
        val result = service.settle(m, charge, boundary)
        return if (result is MembershipPaymentGateway.Result.Declined) {
            m.failedAttempts = 1
            m.transitionTo(Membership.Status.PAST_DUE, "trial conversion declined")
            listOf(Outcome(m.id, Action.TRIAL_FAILED, "${Cents.format(plan.priceCents)} declined (${result.reason})"))
        } else {
            m.transitionTo(Membership.Status.ACTIVE, "trial converted")
            service.benefits.closePeriod(m.id, boundary, plan.rolloverCapMinutes)
            service.grantAllowance(m, plan, boundary)
            auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_RENEWED, "trial converted on $boundary")
            listOf(Outcome(m.id, Action.TRIAL_CONVERTED, "${Cents.format(plan.priceCents)} collected"))
        }
    }

    private fun renew(m: Membership, boundary: LocalDate): List<Outcome> {
        val out = mutableListOf<Outcome>()
        var plan = service.requirePlan(m.planId)

        m.pendingPlanId?.let { pending ->
            val target = service.requirePlan(pending)
            out += Outcome(m.id, Action.DOWNGRADED, "${plan.name} -> ${target.name}")
            m.record("PLAN_CHANGED", "scheduled change ${plan.name} -> ${target.name} applied")
            m.planId = pending
            m.pendingPlanId = null
            plan = target
        }

        val newStart = boundary
        val newEnd = plan.interval.nextPeriodStart(newStart, m.anchorDate)
        val retained = service.benefits.closePeriod(m.id, boundary, plan.rolloverCapMinutes)
        val charge = service.issueCharge(m, MembershipCharge.Kind.RECURRING, plan.priceCents, newStart, newEnd,
            "${plan.name} ${plan.interval.label.lowercase()} renewal", boundary)
        m.currentPeriodStart = newStart
        m.currentPeriodEnd = newEnd
        val result = service.settle(m, charge, boundary)
        if (result is MembershipPaymentGateway.Result.Declined) {
            m.failedAttempts = 1
            m.transitionTo(Membership.Status.PAST_DUE, "renewal declined: ${result.reason}")
            out += Outcome(m.id, Action.RENEWAL_FAILED, "${Cents.format(plan.priceCents)} declined (${result.reason})")
        } else {
            service.grantAllowance(m, plan, boundary)
            val carried = retained.filter { it.type == BenefitEntry.Type.ROLLOVER }.sumOf { it.minutes }
            auditLog?.log(m.id, AuditLog.Action.MEMBERSHIP_RENEWED, "renewed to $newEnd; $carried min rolled over")
            out += Outcome(m.id, Action.RENEWED, "${Cents.format(plan.priceCents)} for $newStart..$newEnd" +
                if (carried > 0) "; $carried min rolled over" else "")
        }
        return out
    }

    companion object {
        /** Safety valve so a membership years behind can't loop forever in one run. */
        private const val MAX_PERIODS_PER_RUN = 36
    }
}
