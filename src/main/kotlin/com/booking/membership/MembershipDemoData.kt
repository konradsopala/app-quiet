package com.booking.membership

import java.time.LocalDate

/**
 * Seeds a realistic membership book for demos: four plans, a spread of
 * members across every lifecycle state, a few consumed benefit minutes and
 * one customer whose card is set to decline.
 *
 * Everything is derived from the supplied [today], so the seeded dates stay
 * sensible whatever the (simulated) clock says. Customer ids are synthetic
 * (`demo-01`..), which means they bypass the customer-directory check the
 * interactive enrol flow performs.
 */
class MembershipDemoData(
    private val service: MembershipService,
    private val gateway: MockMembershipGateway,
    private val scheduler: RenewalScheduler,
) {

    data class Summary(val plans: Int, val members: Int, val notes: List<String>) {
        fun render(): String = buildString {
            appendLine("Seeded $plans plans and $members memberships.")
            notes.forEach { appendLine("  - $it") }
        }.trimEnd()
    }

    fun seed(today: LocalDate): Summary {
        require(service.listPlans(includeRetired = true).isEmpty()) { "Demo data can only be loaded into an empty membership book." }
        val notes = mutableListOf<String>()

        val flex = service.registerPlan(
            MembershipPlan("Flex", BillingInterval.MONTHLY, 2900, trialDays = 7, includedMinutesPerPeriod = 120,
                rolloverCapPercent = 25, bookingDiscountPercent = 5, features = setOf("online-booking")),
        )
        val studio = service.registerPlan(
            MembershipPlan("Studio", BillingInterval.MONTHLY, 5900, setupFeeCents = 2500, includedMinutesPerPeriod = 360,
                rolloverCapPercent = 50, bookingDiscountPercent = 10, features = setOf("priority-slots", "guest-pass")),
        )
        val quarterly = service.registerPlan(
            MembershipPlan("Quarterly Plus", BillingInterval.QUARTERLY, 15000, includedMinutesPerPeriod = 1200,
                rolloverCapPercent = 100, bookingDiscountPercent = 12, maxMembers = 5),
        )
        val annual = service.registerPlan(
            MembershipPlan("Annual Elite", BillingInterval.ANNUAL, 54000, setupFeeCents = 5000, includedMinutesPerPeriod = 5000,
                rolloverCapPercent = 20, bookingDiscountPercent = 20, features = setOf("priority-slots", "guest-pass", "concierge")),
        )
        notes += "Plans: ${service.listPlans().joinToString { it.name }}"

        // Long-standing members, enrolled in the past and rolled forward to today.
        val founding = today.minusMonths(5)
        service.enroll("demo-01", studio.id, founding)
        service.enroll("demo-02", flex.id, founding.plusDays(9))
        service.enroll("demo-03", annual.id, today.minusMonths(3))
        service.enroll("demo-04", quarterly.id, today.minusMonths(4))
        service.enroll("demo-05", studio.id, today.minusMonths(2))
        scheduler.runDue(today)
        notes += "Five founding members, renewed up to $today"

        // Benefit usage.
        service.consumeMinutes("demo-01", 90, today.minusDays(6), "demo-booking-1")
        service.consumeMinutes("demo-03", 400, today.minusDays(12), "demo-booking-2")
        service.consumeMinutes("demo-05", 45, today.minusDays(2), "demo-booking-3")
        notes += "Benefit minutes consumed for demo-01, demo-03 and demo-05"

        // A trial that is still running.
        service.enroll("demo-06", flex.id, today.minusDays(3))
        notes += "demo-06 is mid-trial (Flex)"

        // A member who paused.
        val paused = service.enroll("demo-07", flex.id, today.minusMonths(1).minusDays(5))
        scheduler.runDue(today)
        if (paused.status == Membership.Status.ACTIVE) {
            service.pause(paused.id, today.plusDays(21), today)
            notes += "demo-07 is paused until ${today.plusDays(21)}"
        }

        // Someone who scheduled a cancellation.
        val leaving = service.enroll("demo-08", studio.id, today.minusDays(20))
        service.cancel(leaving.id, today, atPeriodEnd = true, reason = "relocating")
        notes += "demo-08 will cancel at period end ${leaving.currentPeriodEnd}"

        // Someone who already left.
        val gone = service.enroll("demo-09", flex.id, today.minusMonths(4))
        service.cancel(gone.id, today.minusMonths(1), atPeriodEnd = false, reason = "too expensive")
        notes += "demo-09 cancelled last month (too expensive)"

        // A card that will fail at the next renewal.
        val risky = service.enroll("demo-10", studio.id, today.minusDays(25))
        gateway.alwaysDecline("demo-10", MembershipPaymentGateway.Decline.INSUFFICIENT_FUNDS)
        notes += "demo-10 (${risky.id}) will be declined at renewal on ${risky.currentPeriodEnd} — advance the clock and run renewals to watch dunning"

        return Summary(service.listPlans(includeRetired = true).size, service.all().size, notes)
    }
}
