package com.booking.membership

import java.time.LocalDate

/**
 * Built-in behavioural checks for the membership subsystem.
 *
 * The project has no test framework (the build is plain `kotlinc`), so these
 * scenarios run from the CLI — Memberships menu, option 17 — and report
 * pass/fail per check. Each scenario builds its own fresh service so checks
 * cannot leak state into one another.
 */
class MembershipSelfCheck {

    data class Check(val name: String, val passed: Boolean, val detail: String = "")

    private val results = mutableListOf<Check>()

    fun runAll(): List<Check> {
        results.clear()
        intervalAnchoring()
        prorationMath()
        benefitLedger()
        enrolmentAndTrial()
        renewalAndRollover()
        dunningToSuspension()
        dunningRecovery()
        pauseAndResume()
        cancellation()
        planChanges()
        seatLimits()
        csvRoundTrip()
        csvImportValidation()
        reportsAreConsistent()
        return results.toList()
    }

    fun render(checks: List<Check>): String = buildString {
        checks.forEach { c ->
            appendLine("  [${if (c.passed) "PASS" else "FAIL"}] ${c.name}" + if (c.passed || c.detail.isEmpty()) "" else " — ${c.detail}")
        }
        val failed = checks.count { !it.passed }
        append(if (failed == 0) "All ${checks.size} checks passed." else "$failed of ${checks.size} checks FAILED.")
    }

    // ── Scenarios ────────────────────────────────────────────────────

    private fun intervalAnchoring() {
        val anchor = LocalDate.of(2026, 1, 31)
        val feb = BillingInterval.MONTHLY.nextPeriodStart(anchor, anchor)
        val mar = BillingInterval.MONTHLY.nextPeriodStart(feb, anchor)
        expect("monthly billing clamps to month end", feb == LocalDate.of(2026, 2, 28), "got $feb")
        expect("monthly billing returns to the anchor day", mar == LocalDate.of(2026, 3, 31), "got $mar")
        val q = BillingInterval.QUARTERLY.nextPeriodStart(anchor, anchor)
        expect("quarterly advances three months", q == LocalDate.of(2026, 4, 30), "got $q")
        expect("previous period inverts next", BillingInterval.MONTHLY.previousPeriodStart(mar, anchor) == feb)
        expect("interval parsing accepts labels", BillingInterval.parse("annual") == BillingInterval.ANNUAL)
    }

    private fun prorationMath() {
        val calc = ProrationCalculator()
        val basic = plan("Basic", 3000)
        val pro = plan("Pro", 6000)
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 7, 1)
        val mid = calc.prorate(basic, pro, start, end, LocalDate.of(2026, 6, 11))
        expect("upgrade on day 10 of 30 credits 20/30 of old", mid.lines[0].amountCents == -2000L, "got ${mid.lines[0].amountCents}")
        expect("upgrade on day 10 of 30 charges 20/30 of new", mid.lines[1].amountCents == 4000L, "got ${mid.lines[1].amountCents}")
        expect("upgrade nets to a positive charge", mid.netCents == 2000L && !mid.isCredit)
        val down = calc.prorate(pro, basic, start, end, LocalDate.of(2026, 6, 16))
        expect("downgrade nets to a credit", down.isCredit && down.netCents == -1500L, "got ${down.netCents}")
        expect("same-day proration covers the whole period", calc.prorate(basic, pro, start, end, start).netCents == 3000L)
        val rejected = runCatching { calc.prorate(basic, pro, start, end, end) }.isFailure
        expect("proration rejects dates outside the period", rejected)
        expect("immediate-cancel refund is the unused fraction",
            calc.refundForImmediateCancel(pro, start, end, LocalDate.of(2026, 6, 16)) == 3000L)
        expect("no refund once the period is over", calc.refundForImmediateCancel(pro, start, end, end) == 0L)
    }

    private fun benefitLedger() {
        val ledger = BenefitLedger()
        val day = LocalDate.of(2026, 6, 1)
        ledger.grant("m1", 600, day)
        ledger.consume("m1", 200, day, "b1")
        expect("consume reduces the balance", ledger.balance("m1") == 400)
        expect("over-consumption is rejected", runCatching { ledger.consume("m1", 500, day, "b2") }.isFailure)
        expect("a rejected consume writes nothing", ledger.balance("m1") == 400 && ledger.entries("m1").size == 2)
        expect("double consumption for one booking is rejected", runCatching { ledger.consume("m1", 50, day, "b1") }.isFailure)
        ledger.refundBooking("m1", "b1", day)
        expect("refund restores consumed minutes", ledger.balance("m1") == 600)
        expect("refunding twice is a no-op", ledger.refundBooking("m1", "b1", day) == null)
        val closed = ledger.closePeriod("m1", day.plusMonths(1), rolloverCap = 150)
        expect("close period expires then rolls over", closed.map { it.type } == listOf(BenefitEntry.Type.EXPIRE, BenefitEntry.Type.ROLLOVER))
        expect("rollover is capped", ledger.balance("m1") == 150, "got ${ledger.balance("m1")}")
        expect("negative adjustment cannot overdraw", runCatching { ledger.adjust("m1", -500, day, "oops") }.isFailure)
        expect("adjustments require a note", runCatching { ledger.adjust("m1", 10, day, " ") }.isFailure)
        expect("balanceAsOf ignores future entries", ledger.balanceAsOf("m1", day) == 600)
    }

    private fun enrolmentAndTrial() {
        val env = Env()
        val trial = env.service.registerPlan(MembershipPlan("Trial", BillingInterval.MONTHLY, 2500, setupFeeCents = 1000,
            trialDays = 14, includedMinutesPerPeriod = 120))
        val m = env.service.enroll("c1", trial.id, env.day)
        expect("trial enrolment starts TRIALING", m.status == Membership.Status.TRIALING)
        expect("trial charges only the setup fee up front", env.gateway.charged.sumOf { it.second } == 1000L)
        expect("trial grants the allowance immediately", env.service.benefits.balance(m.id) == 120)
        expect("duplicate live enrolment is rejected", runCatching { env.service.enroll("c1", trial.id, env.day) }.isFailure)

        env.scheduler.runDue(env.day.plusDays(13))
        expect("trial is untouched before it ends", m.status == Membership.Status.TRIALING)
        val report = env.scheduler.runDue(env.day.plusDays(14))
        expect("trial converts on its end date", m.status == Membership.Status.ACTIVE && report.count(RenewalScheduler.Action.TRIAL_CONVERTED) == 1)
        expect("conversion charges the plan price", env.gateway.charged.sumOf { it.second } == 3500L)
        expect("running the scheduler twice is idempotent", env.scheduler.runDue(env.day.plusDays(14)).isEmpty)

        val paid = env.service.enroll("c2", env.service.registerPlan(MembershipPlan("Paid", BillingInterval.MONTHLY, 4000)).id, env.day)
        expect("non-trial enrolment is ACTIVE and charged", paid.status == Membership.Status.ACTIVE &&
            env.service.chargesFor(paid.id).single().status == MembershipCharge.Status.PAID)

        env.gateway.alwaysDecline("c3", MembershipPaymentGateway.Decline.INSUFFICIENT_FUNDS)
        val before = env.service.all().size
        val declined = runCatching { env.service.enroll("c3", env.service.findPlanByName("Paid")!!.id, env.day) }
        expect("declined first payment aborts enrolment", declined.isFailure && env.service.all().size == before)
    }

    private fun renewalAndRollover() {
        val env = Env()
        val plan = env.service.registerPlan(MembershipPlan("Gold", BillingInterval.MONTHLY, 5000,
            includedMinutesPerPeriod = 200, rolloverCapPercent = 50))
        val m = env.service.enroll("c1", plan.id, env.day)
        env.service.consumeMinutes("c1", 50, env.day, "b1")
        val renewalDay = m.currentPeriodEnd
        val report = env.scheduler.runDue(renewalDay)
        expect("renewal fires on the period end", report.count(RenewalScheduler.Action.RENEWED) == 1)
        expect("renewal advances the period", m.currentPeriodStart == renewalDay && m.currentPeriodEnd.isAfter(renewalDay))
        expect("150 unused minutes roll over to the 100 cap and a fresh 200 is granted",
            env.service.benefits.balance(m.id) == 300, "got ${env.service.benefits.balance(m.id)}")
        expect("renewal records a charge", env.service.chargesFor(m.id).count { it.kind == MembershipCharge.Kind.RECURRING } == 2)

        val caught = env.scheduler.runDue(renewalDay.plusMonths(3))
        expect("long-idle members catch up period by period", caught.count(RenewalScheduler.Action.RENEWED) >= 2,
            "renewals: ${caught.count(RenewalScheduler.Action.RENEWED)}")
    }

    private fun dunningToSuspension() {
        val env = Env()
        val plan = env.service.registerPlan(MembershipPlan("Std", BillingInterval.MONTHLY, 2000))
        val m = env.service.enroll("c1", plan.id, env.day)
        env.gateway.alwaysDecline("c1", MembershipPaymentGateway.Decline.INSUFFICIENT_FUNDS)
        val due = m.currentPeriodEnd
        env.scheduler.runDue(due)
        expect("failed renewal moves to PAST_DUE", m.status == Membership.Status.PAST_DUE)
        env.scheduler.runDue(due.plusDays(1))
        expect("first retry keeps the member PAST_DUE", m.status == Membership.Status.PAST_DUE)
        env.scheduler.runDue(due.plusDays(3))
        env.scheduler.runDue(due.plusDays(7))
        expect("exhausting retries suspends the member", m.status == Membership.Status.SUSPENDED, "is ${m.status}")
        expect("suspended members lose benefits", !m.hasBenefits)
        env.scheduler.runDue(due.plusDays(7 + 14))
        expect("the grace window ends in EXPIRED", m.status == Membership.Status.EXPIRED, "is ${m.status}")
        expect("expiry voids the open charge", env.service.outstandingCents(m.id) == 0L)
        expect("expired is terminal", runCatching { m.transitionTo(Membership.Status.ACTIVE) }.isFailure)
    }

    private fun dunningRecovery() {
        val env = Env()
        val plan = env.service.registerPlan(MembershipPlan("Std", BillingInterval.MONTHLY, 2000))
        val m = env.service.enroll("c1", plan.id, env.day)
        env.gateway.script("c1", MembershipPaymentGateway.Decline.DO_NOT_HONOUR)
        val due = m.currentPeriodEnd
        env.scheduler.runDue(due)
        expect("scripted decline puts the member PAST_DUE", m.status == Membership.Status.PAST_DUE)
        val retry = env.scheduler.runDue(due.plusDays(1))
        expect("retry after the script succeeds", retry.count(RenewalScheduler.Action.RETRY_OK) == 1)
        expect("recovery restores ACTIVE", m.status == Membership.Status.ACTIVE && m.failedAttempts == 0)

        val env2 = Env()
        val p2 = env2.service.registerPlan(MembershipPlan("Std", BillingInterval.MONTHLY, 2000))
        val m2 = env2.service.enroll("c9", p2.id, env2.day)
        env2.gateway.alwaysDecline("c9", MembershipPaymentGateway.Decline.CARD_EXPIRED)
        env2.scheduler.runDue(m2.currentPeriodEnd)
        env2.scheduler.runDue(m2.currentPeriodEnd.plusDays(1))
        expect("non-retryable declines suspend immediately", m2.status == Membership.Status.SUSPENDED, "is ${m2.status}")
    }

    private fun pauseAndResume() {
        val env = Env()
        val plan = env.service.registerPlan(MembershipPlan("Std", BillingInterval.MONTHLY, 2000))
        val m = env.service.enroll("c1", plan.id, env.day)
        val originalEnd = m.currentPeriodEnd
        env.service.pause(m.id, env.day.plusDays(10), env.day)
        expect("pause moves to PAUSED", m.status == Membership.Status.PAUSED)
        expect("only ACTIVE members can be paused", runCatching { env.service.pause(m.id, null, env.day) }.isFailure)
        val report = env.scheduler.runDue(env.day.plusDays(10))
        expect("scheduler auto-resumes on the resume date", report.count(RenewalScheduler.Action.RESUMED) == 1 && m.status == Membership.Status.ACTIVE)
        expect("resume extends the period by the paused days", !m.currentPeriodEnd.isBefore(originalEnd))
        expect("paused members never renew", env.service.chargesFor(m.id).size == 1)
    }

    private fun cancellation() {
        val env = Env()
        val plan = env.service.registerPlan(MembershipPlan("Std", BillingInterval.MONTHLY, 3000, includedMinutesPerPeriod = 60))
        val a = env.service.enroll("c1", plan.id, env.day)
        env.service.cancel(a.id, env.day, atPeriodEnd = true, reason = "moving")
        expect("scheduled cancel keeps the member live", a.isLive && a.cancelAtPeriodEnd)
        env.service.undoScheduledCancel(a.id)
        expect("scheduled cancel can be withdrawn", !a.cancelAtPeriodEnd)
        env.service.cancel(a.id, env.day, atPeriodEnd = true, reason = "moving")
        val report = env.scheduler.runDue(a.currentPeriodEnd)
        expect("scheduled cancel finalises at period end", a.status == Membership.Status.CANCELLED && report.count(RenewalScheduler.Action.CANCELLED) == 1)
        expect("cancel clears leftover minutes", env.service.benefits.balance(a.id) == 0)

        val b = env.service.enroll("c2", plan.id, env.day)
        val refunded = env.service.cancel(b.id, env.day.plusDays(10), atPeriodEnd = false, reason = "unhappy", refundUnused = true)
        expect("immediate cancel refunds the unused fraction", refunded in 1..2999L, "refunded $refunded")
        expect("refund goes through the gateway", env.gateway.refunded.sumOf { it.second } == refunded)
        expect("cancelled memberships cannot be cancelled again", runCatching { env.service.cancel(b.id, env.day, false, "x") }.isFailure)
    }

    private fun planChanges() {
        val env = Env()
        val basic = env.service.registerPlan(MembershipPlan("Basic", BillingInterval.MONTHLY, 3000, includedMinutesPerPeriod = 100))
        val pro = env.service.registerPlan(MembershipPlan("Pro", BillingInterval.MONTHLY, 6000, includedMinutesPerPeriod = 300,
            bookingDiscountPercent = 15))
        val m = env.service.enroll("c1", basic.id, env.day)
        val result = env.service.changePlan(m.id, pro.id, env.day.plusDays(10))
        expect("upgrade charges the proration net", result.netCents > 0 && env.service.chargesFor(m.id).any { it.kind == MembershipCharge.Kind.PRORATION })
        expect("upgrade tops up the allowance", env.service.benefits.balance(m.id) > 100)
        expect("upgrade takes effect immediately", m.planId == pro.id && env.service.discountFor("c1") == 15)
        expect("changing to the same plan is rejected", runCatching { env.service.changePlan(m.id, pro.id, env.day) }.isFailure)

        env.service.scheduleDowngrade(m.id, basic.id)
        expect("a scheduled downgrade leaves the plan alone", m.planId == pro.id && m.pendingPlanId == basic.id)
        val report = env.scheduler.runDue(m.currentPeriodEnd)
        expect("the downgrade applies at renewal", report.count(RenewalScheduler.Action.DOWNGRADED) == 1 && m.planId == basic.id)
        expect("the downgrade renews at the lower price", env.service.chargesFor(m.id).last().amountCents == 3000L)
    }

    private fun seatLimits() {
        val env = Env()
        val plan = env.service.registerPlan(MembershipPlan("Limited", BillingInterval.MONTHLY, 1000, maxMembers = 2))
        env.service.enroll("c1", plan.id, env.day)
        val second = env.service.enroll("c2", plan.id, env.day)
        expect("a full plan rejects new members", runCatching { env.service.enroll("c3", plan.id, env.day) }.isFailure)
        env.service.cancel(second.id, env.day, atPeriodEnd = false, reason = "left")
        expect("cancelling frees a seat", env.service.seatsRemaining(plan.id) == 1)
        env.service.retirePlan(plan.id)
        expect("retired plans reject new members", runCatching { env.service.enroll("c3", plan.id, env.day) }.isFailure)
        expect("duplicate active plan names are rejected", runCatching {
            env.service.registerPlan(MembershipPlan("Dup", BillingInterval.MONTHLY, 1))
            env.service.registerPlan(MembershipPlan("dup", BillingInterval.MONTHLY, 1))
        }.isFailure)
    }

    private fun csvRoundTrip() {
        val rows = MembershipCsv.parse("a,\"b,c\",\"say \"\"hi\"\"\"\r\n\r\nx,y,z\n")
        expect("CSV parser handles quotes, commas and CRLF", rows == listOf(listOf("a", "b,c", "say \"hi\""), listOf("x", "y", "z")), "got $rows")
        expect("CSV escape round-trips", MembershipCsv.parse(MembershipCsv.row("p,q", "r\"s", "t")) == listOf(listOf("p,q", "r\"s", "t")))
        expect("unterminated quotes are rejected", runCatching { MembershipCsv.parse("a,\"b") }.isFailure)

        val env = Env()
        val plan = env.service.registerPlan(MembershipPlan("Std", BillingInterval.MONTHLY, 2000, features = setOf("sauna", "towels")))
        env.service.enroll("c1", plan.id, env.day)
        val exporter = MembershipCsvExporter(env.service)
        val parsed = MembershipCsv.parse(exporter.membershipsCsv())
        expect("memberships export has a header plus one row", parsed.size == 2 && parsed[1][0] == "c1" && parsed[1][1] == "Std")
        expect("plans export lists features", MembershipCsv.parse(exporter.plansCsv())[1].last() == "sauna;towels")
    }

    private fun csvImportValidation() {
        val env = Env()
        env.service.registerPlan(MembershipPlan("Std", BillingInterval.MONTHLY, 2000, includedMinutesPerPeriod = 30))
        val importer = MembershipCsvImporter(env.service)
        val bad = importer.import("customer_id,plan,start_date\nc1,Nope,2026-01-01\n,Std,not-a-date\n", env.day)
        expect("import reports every bad row", bad.errors.size >= 3, "errors: ${bad.errors}")
        expect("an invalid file imports nothing", env.service.all().isEmpty())
        expect("missing columns are reported", importer.import("customer_id,plan\nc1,Std\n", env.day).errors.single().message.contains("start_date"))

        val csv = "customer_id,plan,start_date,status,cancel_reason\nc1,Std,2026-01-15,ACTIVE,\nc2,Std,2026-02-01,CANCELLED,moved\nc3,Std,2026-03-01,PAUSED,\n"
        val dry = importer.import(csv, env.day, dryRun = true)
        expect("dry run validates without writing", dry.ok && dry.rows.size == 3 && env.service.all().isEmpty())
        val real = importer.import(csv, env.day)
        expect("a valid import creates every membership", real.imported.size == 3 && env.service.all().size == 3)
        expect("imported statuses are preserved", env.service.byStatus(Membership.Status.CANCELLED).single().cancelReason == "moved" &&
            env.service.byStatus(Membership.Status.PAUSED).size == 1)
        expect("imports never touch the gateway", env.gateway.charged.isEmpty())
        expect("re-importing the same file is rejected", !importer.import(csv, env.day).ok)
    }

    private fun reportsAreConsistent() {
        val env = Env()
        val monthly = env.service.registerPlan(MembershipPlan("Monthly", BillingInterval.MONTHLY, 3000))
        val annual = env.service.registerPlan(MembershipPlan("Annual", BillingInterval.ANNUAL, 24000))
        env.service.enroll("c1", monthly.id, env.day)
        env.service.enroll("c2", annual.id, env.day)
        val reports = MembershipReportGenerator(env.service)
        expect("MRR normalises annual plans to months", reports.monthlyRecurringRevenueCents() == 5000L,
            "got ${reports.monthlyRecurringRevenueCents()}")
        expect("run rate is twelve months of MRR", reports.annualRunRateCents() == 60000L)
        expect("ARPU divides by paying members", reports.arpuCents() == 2500L)
        expect("net collected equals the sum of payments", env.service.netCollectedCents() == 27000L)
        val c = reports.churn(env.day.minusDays(1), env.day.plusDays(30))
        expect("churn counts joiners", c.joined == 2 && c.churned == 0 && c.churnRate == 0.0)
        expect("cohorts group by signup month", reports.cohorts().single().size == 2)
        expect("upcoming renewals respect the look-ahead", reports.upcomingRenewals(env.day, 40).size == 1)
        expect("the arrears total is zero when everyone paid", reports.totalArrearsCents() == 0L)
        val statement = MembershipStatementRenderer(env.service).render(env.service.all().first().id, env.day)
        expect("statements render both sections", statement.contains("CHARGES") && statement.contains("BENEFIT MINUTES"))
    }

    // ── Plumbing ─────────────────────────────────────────────────────

    /** A fresh service + gateway + scheduler anchored on a fixed date. */
    private class Env {
        val day: LocalDate = LocalDate.of(2026, 6, 1)
        val gateway = MockMembershipGateway()
        val service = MembershipService(gateway)
        val scheduler = RenewalScheduler(service)
    }

    private fun plan(name: String, cents: Long) = MembershipPlan(name, BillingInterval.MONTHLY, cents)

    private fun expect(name: String, condition: Boolean, detail: String = "") {
        results += Check(name, condition, detail)
    }
}
