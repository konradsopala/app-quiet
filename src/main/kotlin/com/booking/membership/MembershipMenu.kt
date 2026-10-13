package com.booking.membership

import com.booking.service.AuditLog
import com.booking.service.CustomerService
import com.booking.util.Cents
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Scanner

/**
 * Interactive console front-end for the membership subsystem.
 *
 * The menu works against a *simulated* "today" (see [today]) so renewals,
 * trials and dunning can be exercised in a demo without waiting for real
 * time to pass: advance the clock, then run the renewal scheduler.
 */
class MembershipMenu(
    private val scanner: Scanner,
    private val service: MembershipService,
    private val customers: CustomerService,
    private val gateway: MockMembershipGateway,
    private val scheduler: RenewalScheduler,
    private val reports: MembershipReportGenerator = MembershipReportGenerator(service),
    private val statements: MembershipStatementRenderer = MembershipStatementRenderer(service),
    private val importer: MembershipCsvImporter = MembershipCsvImporter(service),
    private val exporter: MembershipCsvExporter = MembershipCsvExporter(service),
    private val auditLog: AuditLog? = null,
) {
    /** The simulated current date used by every time-sensitive action. */
    var today: LocalDate = LocalDate.now()
        private set

    fun run() {
        while (true) {
            println(
                """
                |
                |=== Memberships (today: $today) ===
                |  1) Plans            2) Enrol a customer   3) List memberships
                |  4) Membership detail 5) Pause / resume      6) Cancel
                |  7) Change plan      8) Schedule downgrade   9) Run renewals
                |  10) Advance clock   11) Benefit minutes    12) Statement
                |  13) Reports         14) Import CSV         15) Export CSV
                |  16) Simulate payment failure  17) Run self-check
                |  18) Load demo data                          (blank) back
                """.trimMargin(),
            )
            print("Choice: ")
            when (scanner.nextLine().trim()) {
                "" -> return
                "1" -> managePlans()
                "2" -> enrol()
                "3" -> listMemberships()
                "4" -> showDetail()
                "5" -> pauseOrResume()
                "6" -> cancel()
                "7" -> changePlan()
                "8" -> scheduleDowngrade()
                "9" -> runRenewals()
                "10" -> advanceClock()
                "11" -> benefitMinutes()
                "12" -> statement()
                "13" -> showReports()
                "14" -> importCsv()
                "15" -> exportCsv()
                "16" -> simulateFailure()
                "17" -> runSelfCheck()
                "18" -> loadDemoData()
                else -> println("Invalid choice.")
            }
        }
    }

    // ── Plans ────────────────────────────────────────────────────────

    private fun managePlans() {
        println("Plans: a) list  b) create  c) retire  d) reactivate  (blank) cancel")
        print("Choice: ")
        when (scanner.nextLine().trim().lowercase()) {
            "a" -> println(reports.renderPlanBreakdown())
            "b" -> createPlan()
            "c" -> guarded { service.retirePlan(prompt("Plan id")); println("Plan retired.") }
            "d" -> guarded { service.reactivatePlan(prompt("Plan id")); println("Plan reactivated.") }
            "" -> {}
            else -> println("Invalid choice.")
        }
    }

    private fun createPlan() = guarded {
        val name = prompt("Name")
        val interval = BillingInterval.parse(prompt("Interval (MONTHLY/QUARTERLY/ANNUAL)"))
        val price = Cents.parse(prompt("Price (e.g. 29.00)"))
        val setup = promptOptional("Setup fee [0]")?.let { Cents.parse(it) } ?: 0L
        val trial = promptOptional("Trial days [0]")?.toInt() ?: 0
        val minutes = promptOptional("Included minutes per period [0]")?.toInt() ?: 0
        val rollover = promptOptional("Rollover cap percent [0]")?.toInt() ?: 0
        val discount = promptOptional("Booking discount percent [0]")?.toInt() ?: 0
        val seats = promptOptional("Max members [unlimited]")?.toInt()
        val features = promptOptional("Features (comma separated)")?.split(",")?.toSet() ?: emptySet()
        val plan = MembershipPlan(
            name = name, interval = interval, priceCents = price, setupFeeCents = setup, trialDays = trial,
            includedMinutesPerPeriod = minutes, rolloverCapPercent = rollover, bookingDiscountPercent = discount,
            maxMembers = seats, features = features,
        )
        service.registerPlan(plan)
        println("Created $plan")
    }

    // ── Enrolment & lookup ───────────────────────────────────────────

    private fun enrol() = guarded {
        val plans = service.listPlans()
        if (plans.isEmpty()) {
            println("No plans yet — create one first.")
            return@guarded
        }
        plans.forEachIndexed { i, p -> println("  ${i + 1}) $p") }
        val plan = plans[prompt("Plan number").toInt() - 1]
        val customerId = pickCustomer()
        val start = promptOptional("Start date [$today]")?.let(LocalDate::parse) ?: today
        val m = service.enroll(customerId, plan.id, start)
        println("Enrolled: $m")
        if (m.status == Membership.Status.TRIALING) println("Trial runs until ${m.trialEnd}.")
    }

    private fun pickCustomer(): String {
        val all = customers.list()
        if (all.isNotEmpty()) {
            println("Known customers:")
            all.take(10).forEach { println("  ${it.id}  ${it.name}") }
        }
        val id = prompt("Customer id")
        require(customers.find(id) != null) { "Unknown customer '$id'." }
        return id
    }

    private fun listMemberships() {
        val all = service.all()
        if (all.isEmpty()) {
            println("No memberships yet.")
            return
        }
        println("Filter by status (blank = all): ")
        val filter = scanner.nextLine().trim().uppercase()
        val shown = if (filter.isEmpty()) all else all.filter { it.status.name == filter }
        if (shown.isEmpty()) println("Nothing matches '$filter'.") else shown.forEach(::println)
    }

    private fun showDetail() = guarded {
        println(reports.renderMembershipDetail(prompt("Membership id"), today))
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    private fun pauseOrResume() = guarded {
        val id = prompt("Membership id")
        val m = service.require(id)
        when (m.status) {
            Membership.Status.ACTIVE -> {
                val until = promptOptional("Resume on (blank = until resumed manually)")?.let(LocalDate::parse)
                service.pause(id, until, today)
                println("Paused.")
            }
            Membership.Status.PAUSED -> {
                service.resume(id, today)
                println("Resumed; period now ends ${m.currentPeriodEnd}.")
            }
            else -> println("Cannot pause or resume a ${m.status} membership.")
        }
    }

    private fun cancel() = guarded {
        val id = prompt("Membership id")
        val reason = prompt("Reason")
        val atEnd = prompt("Cancel at period end? (y/n)").lowercase().startsWith("y")
        val refund = !atEnd && prompt("Refund unused time? (y/n)").lowercase().startsWith("y")
        val refunded = service.cancel(id, today, atEnd, reason, refund)
        println(if (atEnd) "Cancellation scheduled." else "Cancelled. Refunded ${Cents.format(refunded)}.")
    }

    private fun changePlan() = guarded {
        val id = prompt("Membership id")
        service.listPlans().forEach { println("  ${it.id}  $it") }
        val result = service.changePlan(id, prompt("New plan id"), today)
        println(result.render())
    }

    private fun scheduleDowngrade() = guarded {
        val id = prompt("Membership id")
        service.listPlans().forEach { println("  ${it.id}  $it") }
        service.scheduleDowngrade(id, prompt("Plan to switch to at renewal"))
        println("Scheduled.")
    }

    // ── Time ─────────────────────────────────────────────────────────

    private fun runRenewals() {
        println(scheduler.runDue(today).render())
    }

    private fun advanceClock() = guarded {
        val text = prompt("Days to advance (or a date)")
        today = if (text.contains("-")) LocalDate.parse(text) else today.plusDays(text.toLong())
        println("Clock is now $today.")
        if (prompt("Run renewals now? (y/n)").lowercase().startsWith("y")) runRenewals()
    }

    private fun simulateFailure() = guarded {
        val customerId = prompt("Customer id")
        val reasonText = prompt("Decline reason ${MembershipPaymentGateway.Decline.values().joinToString("/")} (blank = clear)")
        if (reasonText.isEmpty()) {
            gateway.clear(customerId)
            println("Customer $customerId will be approved again.")
        } else {
            gateway.alwaysDecline(customerId, MembershipPaymentGateway.Decline.valueOf(reasonText.uppercase()))
            println("Customer $customerId will now be declined with $reasonText.")
        }
    }

    private fun loadDemoData() = guarded {
        println(MembershipDemoData(service, gateway, scheduler).seed(today).render())
    }

    private fun runSelfCheck() {
        val checker = MembershipSelfCheck()
        println(checker.render(checker.runAll()))
    }

    // ── Benefits & statements ────────────────────────────────────────

    private fun benefitMinutes() = guarded {
        println("Benefits: a) balance  b) consume  c) refund  d) adjust  (blank) cancel")
        print("Choice: ")
        when (scanner.nextLine().trim().lowercase()) {
            "a" -> {
                val id = prompt("Membership id")
                service.benefits.entries(id).forEach(::println)
                println("Balance: ${service.benefits.balance(id)} min")
            }
            "b" -> {
                val draws = service.consumeMinutes(prompt("Customer id"), prompt("Minutes").toInt(), today, prompt("Booking id"))
                draws.forEach { (mid, n) -> println("  $n min from $mid") }
            }
            "c" -> println("Refunded ${service.refundMinutes(prompt("Customer id"), prompt("Booking id"), today)} min.")
            "d" -> {
                val id = prompt("Membership id")
                service.benefits.adjust(id, prompt("Minutes (+/-)").toInt(), today, prompt("Note"))
                println("Balance: ${service.benefits.balance(id)} min")
            }
            "" -> {}
            else -> println("Invalid choice.")
        }
    }

    private fun statement() = guarded {
        println(statements.render(prompt("Membership id"), today))
    }

    // ── Reports ──────────────────────────────────────────────────────

    private fun showReports() = guarded {
        println("Reports: a) summary  b) plans  c) churn  d) cohorts  e) arrears  f) upcoming  g) cancel reasons")
        print("Choice: ")
        when (scanner.nextLine().trim().lowercase()) {
            "a" -> println(reports.renderSummary(today))
            "b" -> println(reports.renderPlanBreakdown())
            "c" -> {
                val from = promptOptional("From [${today.minusDays(30)}]")?.let(LocalDate::parse) ?: today.minusDays(30)
                println(reports.renderChurn(reports.churn(from, today)))
            }
            "d" -> println(reports.renderCohorts())
            "e" -> println(reports.renderArrears(today))
            "f" -> println(reports.renderUpcoming(today, promptOptional("Days ahead [30]")?.toInt() ?: 30))
            "g" -> println(reports.renderCancelReasons())
            "" -> {}
            else -> println("Invalid choice.")
        }
    }

    // ── CSV ──────────────────────────────────────────────────────────

    private fun importCsv() = guarded {
        val path = prompt("CSV path")
        val file = File(path)
        if (!file.isFile) {
            println("File not found: $path")
            return@guarded
        }
        val dry = importer.import(file.readText(), today, dryRun = true)
        println(dry.render())
        if (dry.ok && dry.rows.isNotEmpty() && prompt("Apply import? (y/n)").lowercase().startsWith("y")) {
            println(importer.import(file.readText(), today, dryRun = false).render())
        }
    }

    private fun exportCsv() = guarded {
        val dir = promptOptional("Output directory [.]") ?: "."
        try {
            exporter.writeTo(File(dir, "membership_plans.csv"), exporter.plansCsv())
            exporter.writeTo(File(dir, "memberships.csv"), exporter.membershipsCsv())
            exporter.writeTo(File(dir, "membership_charges.csv"), exporter.chargesCsv())
            println("Wrote membership_plans.csv, memberships.csv and membership_charges.csv to $dir")
        } catch (e: IOException) {
            println("Export failed: ${e.message}")
        }
    }

    // ── Input helpers ────────────────────────────────────────────────

    private fun prompt(label: String): String {
        print("$label: ")
        return scanner.nextLine().trim()
    }

    private fun promptOptional(label: String): String? {
        print("$label: ")
        return scanner.nextLine().trim().ifEmpty { null }
    }

    /** Run [block], turning the usual validation failures into a friendly message. */
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
        } catch (e: IllegalStateException) {
            println("Error: ${e.message}")
        } catch (e: DateTimeParseException) {
            println("Invalid date: ${e.parsedString}")
        } catch (e: IndexOutOfBoundsException) {
            println("Error: that number is not in the list.")
        }
    }
}
