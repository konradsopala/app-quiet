package com.booking.membership

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A customer's subscription to a [MembershipPlan].
 *
 * Lifecycle is a small state machine; [transitionTo] rejects illegal moves so
 * services cannot, for example, resurrect an [Status.EXPIRED] membership.
 *
 * ```
 *   TRIALING ──► ACTIVE ◄──► PAST_DUE ──► SUSPENDED ──► EXPIRED
 *      │           │  ▲          │
 *      │           ▼  │          │
 *      │         PAUSED          │
 *      └──────────► CANCELLED ◄──┘
 * ```
 */
class Membership(
    val customerId: String,
    planId: String,
    val startDate: LocalDate,
    id: String? = null,
) {
    enum class Status(val terminal: Boolean = false) {
        TRIALING,
        ACTIVE,
        PAST_DUE,
        PAUSED,
        SUSPENDED,
        CANCELLED(terminal = true),
        EXPIRED(terminal = true),
    }

    /** One entry in the membership's own audit trail. */
    data class Event(
        val at: LocalDateTime,
        val kind: String,
        val detail: String,
    ) {
        override fun toString(): String = "[$at] $kind: $detail"
    }

    val id: String = id ?: ("mem_" + UUID.randomUUID().toString().replace("-", "").take(14))

    var planId: String = planId
        internal set

    var status: Status = Status.ACTIVE
        private set

    /** The date billing periods are anchored to; fixed at creation. */
    val anchorDate: LocalDate = startDate

    var currentPeriodStart: LocalDate = startDate
        internal set

    var currentPeriodEnd: LocalDate = startDate
        internal set

    /** Set when the member is in a trial; the first charge happens on this date. */
    var trialEnd: LocalDate? = null
        internal set

    /** When true, the membership ends at [currentPeriodEnd] instead of renewing. */
    var cancelAtPeriodEnd: Boolean = false
        internal set

    var cancelReason: String? = null
        internal set

    /** Date a paused membership automatically resumes; null means "until resumed manually". */
    var pausedUntil: LocalDate? = null
        internal set

    /** Plan the member will move to at the next renewal (a scheduled downgrade). */
    var pendingPlanId: String? = null
        internal set

    var endedOn: LocalDate? = null
        internal set

    /** Business date the membership was last paused; drives period extension on resume. */
    var pausedOn: LocalDate? = null
        internal set

    /** Business date the membership was suspended for non-payment; drives the expiry grace window. */
    var suspendedOn: LocalDate? = null
        internal set

    /** Number of consecutive failed renewal attempts for the open charge. */
    var failedAttempts: Int = 0
        internal set

    private val _events = mutableListOf<Event>()
    val events: List<Event> get() = _events.toList()

    val isLive: Boolean get() = !status.terminal

    /** True when the member currently holds benefits (discounts, minutes). */
    val hasBenefits: Boolean
        get() = status == Status.TRIALING || status == Status.ACTIVE || status == Status.PAST_DUE

    fun transitionTo(next: Status, reason: String = "") {
        require(next in ALLOWED.getValue(status)) { "Illegal membership transition $status -> $next" }
        val previous = status
        status = next
        record("STATUS", "$previous -> $next" + if (reason.isBlank()) "" else " ($reason)")
    }

    /** Initial-state hook for enrolment: a trial is the only status a new membership may start in besides ACTIVE. */
    internal fun beginTrial(reason: String) {
        check(status == Status.ACTIVE && events.none { it.kind == "STATUS" }) { "A trial can only begin on a brand-new membership." }
        status = Status.TRIALING
        record("STATUS", "ACTIVE -> TRIALING ($reason)")
    }

    internal fun record(kind: String, detail: String, at: LocalDateTime = LocalDateTime.now()) {
        _events.add(Event(at, kind, detail))
    }

    fun daysRemainingInPeriod(asOf: LocalDate): Long =
        (currentPeriodEnd.toEpochDay() - asOf.toEpochDay()).coerceAtLeast(0)

    override fun toString(): String {
        val pause = pausedUntil?.let { " | resumes $it" } ?: ""
        val cancel = if (cancelAtPeriodEnd) " | cancels at period end" else ""
        return "[$id] cust:$customerId plan:$planId | $status | $currentPeriodStart..$currentPeriodEnd$pause$cancel"
    }

    companion object {
        private val ALLOWED: Map<Status, Set<Status>> = mapOf(
            Status.TRIALING to setOf(Status.ACTIVE, Status.PAST_DUE, Status.CANCELLED),
            Status.ACTIVE to setOf(Status.PAST_DUE, Status.PAUSED, Status.CANCELLED),
            Status.PAST_DUE to setOf(Status.ACTIVE, Status.SUSPENDED, Status.CANCELLED),
            Status.PAUSED to setOf(Status.ACTIVE, Status.CANCELLED),
            Status.SUSPENDED to setOf(Status.ACTIVE, Status.EXPIRED, Status.CANCELLED),
            Status.CANCELLED to emptySet(),
            Status.EXPIRED to emptySet(),
        )

        /** Exposed so reports and tests can enumerate the legal transitions. */
        fun allowedTransitions(from: Status): Set<Status> = ALLOWED.getValue(from)
    }
}
