package com.booking

import com.booking.config.AppConfig
import com.booking.model.Booking
import com.booking.notification.ConsoleNotifier
import com.booking.notification.EmailNotifier
import com.booking.notification.NotificationDispatcher
import com.booking.notification.NotificationEvent
import com.booking.notification.NotificationPreferences
import com.booking.notification.SmsNotifier
import com.booking.service.AnalyticsEngine
import com.booking.service.AuditLog
import com.booking.service.BookingPricer
import com.booking.service.BookingService
import com.booking.service.BookingValidator
import com.booking.service.CancellationService
import com.booking.service.CustomerService
import com.booking.service.ICalExporter
import com.booking.service.LoyaltyEngine
import com.booking.service.MockPaymentProcessor
import com.booking.service.NotificationService
import com.booking.service.PaymentService
import com.booking.service.RecurringBookingService
import com.booking.service.RefundReceiptExporter
import com.booking.service.ReminderScheduler
import com.booking.service.ReportGenerator
import com.booking.persistence.SnapshotStore
import com.booking.service.StatisticsService
import com.booking.service.WaitlistService
import com.booking.util.BookingFilter
import com.booking.util.TextTable
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.Scanner

fun main() {
    App().run()
}

class App(private val config: AppConfig = AppConfig.DEFAULT) {

    private val service = BookingService(config)
    private val validator = BookingValidator(service, config)
    private val reportGenerator = ReportGenerator(service)
    private val customers = CustomerService()
    private val pricer = BookingPricer(service, customers)
    private val recurring = RecurringBookingService(service, validator)
    private val waitlist = WaitlistService(service, validator)
    private val payments = PaymentService(service, MockPaymentProcessor())
    private val cancellations = CancellationService(service, payments, customers)
    private val receipts = RefundReceiptExporter(service, customers)
    private val ical = ICalExporter(service, customerDirectory = customers)
    private val stats = StatisticsService(service)
    private val snapshots = SnapshotStore(service, customers, pricer.couponRegistry, payments, waitlist)
    private val notifications = NotificationDispatcher().apply {
        register(ConsoleNotifier())
        // Email and SMS are wired up but disabled by default — flip them on
        // from the channel-management menu (option 24).
        register(EmailNotifier(), enabled = false)
        register(SmsNotifier(), enabled = false)
    }
    private val reminderBus = NotificationService()
    private val reminders = ReminderScheduler(reminderBus)
    private val analytics = AnalyticsEngine(service)
    private val loyalty = LoyaltyEngine(service)
    private val scanner = Scanner(System.`in`)

    fun run() {
        println("=== Booking Manager v3 — Notifications & Insights ===")

        while (true) {
            println("""
                |
                | 1) Create booking
                | 2) List bookings
                | 3) Find booking
                | 4) Cancel booking
                | 5) Search by customer
                | 6) Update booking
                | 7) Statistics
                | 8) Export to CSV
                | 9) Generate report
                |10) Advanced search
                |11) View audit log
                |12) Booking history
                |13) Quote price
                |14) Set capacity (current: ${service.capacity})
                |15) Create recurring series
                |16) Cancel recurring series
                |17) View waitlist (${waitlist.size()})
                |18) Remove from waitlist
                |19) Create payment intent
                |20) Confirm payment
                |21) Refund payment
                |22) List payments
                |23) Export to iCalendar (.ics)
                |24) Manage notification channels
                |25) Manage customer notification preferences
                |26) Manage coupons (${pricer.couponRegistry.size()})
                |27) Save snapshot
                |28) Load snapshot
                |29) Cancel with refund policy
                |30) Exit
            """.trimMargin())
            print("\nChoice: ")

            when (scanner.nextLine().trim()) {
                "1"  -> createBooking()
                "2"  -> listBookings()
                "3"  -> findBooking()
                "4"  -> cancelBooking()
                "5"  -> searchByCustomer()
                "6"  -> updateBooking()
                "7"  -> showStatistics()
                "8"  -> exportToCsv()
                "9"  -> generateReport()
                "10" -> advancedSearch()
                "11" -> viewAuditLog()
                "12" -> viewBookingHistory()
                "13" -> quotePrice()
                "14" -> setCapacity()
                "15" -> createRecurringSeries()
                "16" -> cancelRecurringSeries()
                "17" -> viewWaitlist()
                "18" -> removeFromWaitlist()
                "19" -> createPaymentIntent()
                "20" -> confirmPayment()
                "21" -> refundPayment()
                "22" -> listPayments()
                "23" -> exportICal()
                "24" -> manageNotificationChannels()
                "25" -> manageCustomerPreferences()
                "26" -> manageCoupons()
                "27" -> saveSnapshot()
                "28" -> loadSnapshot()
                "29" -> cancelWithPolicy()
                "30" -> { println("Goodbye!"); return }
                else -> println("Invalid choice.")
            }
        }
    }

    // ── 1. Create ──────────────────────────────────────────────────

    private fun createBooking() {
        print("Customer name: ")
        val name = scanner.nextLine().trim()

        print("Date (YYYY-MM-DD): ")
        val date: LocalDate
        try {
            date = LocalDate.parse(scanner.nextLine().trim())
        } catch (e: DateTimeParseException) {
            println("Invalid date format."); return
        }

        print("Start time (HH:MM, 24h): ")
        val startTime: LocalTime
        try {
            startTime = LocalTime.parse(scanner.nextLine().trim())
        } catch (e: DateTimeParseException) {
            println("Invalid time format."); return
        }

        print("Duration in minutes: ")
        val duration = scanner.nextLine().trim().toIntOrNull()
        if (duration == null || duration <= 0) {
            println("Duration must be a positive integer."); return
        }

        print("Description: ")
        val description = scanner.nextLine().trim()

        print("Tags (comma-separated, blank to skip): ")
        val tagInput = scanner.nextLine().trim()
        val tags = if (tagInput.isEmpty()) emptySet() else
            tagInput.split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()

        print("Internal notes (blank to skip): ")
        val notes = scanner.nextLine().trim().ifEmpty { null }

        print("External/internal reference (blank to skip): ")
        val reference = scanner.nextLine().trim().ifEmpty { null }

        val resourceId = promptForResource()

        val result = validator.validateNewBooking(
            name, date, startTime, duration, description, tags, reference, resourceId
        )
        if (!result.valid) {
            println("Validation failed:")
            result.errors.forEach { println("  - $it") }
            // Offer waitlist iff the only thing blocking is capacity.
            val onlyCapacity = result.errors.isNotEmpty() &&
                result.errors.all { it.contains("Time slot is full") }
            if (onlyCapacity) {
                print("\nAdd to waitlist? (y/n): ")
                if (scanner.nextLine().trim().equals("y", ignoreCase = true)) {
                    print("Priority (LOW/NORMAL/HIGH/VIP, blank for NORMAL): ")
                    val pInput = scanner.nextLine().trim().uppercase()
                    val priority = if (pInput.isEmpty()) {
                        com.booking.model.WaitlistEntry.Priority.NORMAL
                    } else try {
                        com.booking.model.WaitlistEntry.Priority.valueOf(pInput)
                    } catch (e: IllegalArgumentException) {
                        println("Unknown priority '$pInput', using NORMAL.")
                        com.booking.model.WaitlistEntry.Priority.NORMAL
                    }
                    try {
                        val entry = waitlist.add(name, date, startTime, duration, description, priority)
                        println("Added to waitlist: $entry")
                    } catch (e: IllegalArgumentException) {
                        println("Could not waitlist: ${e.message}")
                    }
                }
            }
            return
        }

        // Offer to link this booking to an existing customer record. We
        // auto-suggest only when there's a single unambiguous name match
        // — multi-match or fuzzy hits would require a richer picker and
        // are out of scope for this prompt.
        val linkedCustomerId = resolveCustomerIdForName(name)

        try {
            val booking = service.createBooking(
                name, date, startTime, duration, description,
                tags = tags, notes = notes, internalReference = reference,
                customerId = linkedCustomerId
            )
            println("Booking created: $booking")
            notifications.dispatch(NotificationEvent.BookingCreated(booking))
            val scheduled = reminders.scheduleFor(booking)
            if (scheduled.attempted > 0) println("  $scheduled")
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
        }
    }

    /**
     * Best-effort match of [name] against the customer directory. If
     * there's exactly one customer with that name, prompt to link
     * (default yes). Otherwise return null and leave the booking
     * un-linked — the operator can still call linkCustomer later.
     */
    private fun resolveCustomerIdForName(name: String): String? {
        if (customers.size() == 0) return null
        val match = customers.findByExactName(name) ?: return null
        print("Link to existing customer ${match.name} [${match.id}]? (Y/n): ")
        val answer = scanner.nextLine().trim().lowercase()
        return if (answer == "n" || answer == "no") null else match.id
    }

    // ── 2. List ────────────────────────────────────────────────────

    private fun listBookings() {
        val bookings = service.listBookings()
        if (bookings.isEmpty()) { println("No bookings found."); return }
        bookings.forEach(::println)
    }

    // ── 3. Find ────────────────────────────────────────────────────

    private fun findBooking() {
        print("Booking ID: ")
        val id = scanner.nextLine().trim()
        val booking = service.findBooking(id)
        if (booking == null) println("Booking not found.") else println(booking)
    }

    // ── 4. Cancel ──────────────────────────────────────────────────

    private fun cancelBooking() {
        print("Booking ID to cancel: ")
        val id = scanner.nextLine().trim()
        val booking = service.findBooking(id)
        if (booking != null && service.cancelBooking(id)) {
            println("Booking cancelled.")
            notifications.dispatch(NotificationEvent.BookingCancelled(booking))
            autoRefundForBooking(id, booking)
            promoteWaitlistIfAny()
        } else {
            println("Booking not found or already cancelled.")
        }
    }

    // ── 29. Cancel with refund policy ──────────────────────────────

    private fun cancelWithPolicy() {
        println("Policy: ${cancellations.policy}")
        print("Booking ID to cancel: ")
        val id = scanner.nextLine().trim()
        val booking = service.findBooking(id)
        if (booking == null) { println("Booking not found."); return }
        if (booking.status == Booking.Status.CANCELLED) {
            println("Booking is already cancelled."); return
        }

        // Preview the fee/refund split before committing anything.
        val preview = try {
            cancellations.quote(id)
        } catch (e: IllegalArgumentException) {
            println("Cannot quote: ${e.message}"); return
        }
        println("\n$preview")
        if (!preview.hasPayments && preview.chargedAmount == 0.0) {
            println("(No payment or quote on file — nothing to refund.)")
        }

        print("\nProceed with cancellation? (y/N): ")
        if (!scanner.nextLine().trim().equals("y", ignoreCase = true)) {
            println("Cancellation aborted."); return
        }

        val result = cancellations.cancel(id)
        if (result == null) {
            println("Could not cancel (not found or already cancelled)."); return
        }
        println("Booking cancelled. ${result.quote}")
        notifications.dispatch(NotificationEvent.BookingCancelled(booking))
        if (result.refunded.isNotEmpty()) {
            println("Refunded ${result.refunded.size} payment(s):")
            result.refunded.forEach { intent ->
                println("  ↩ $intent")
                notifications.dispatch(NotificationEvent.PaymentRefunded(intent, booking))
            }
        }
        if (result.refundFailures.isNotEmpty()) {
            println("Refund failed for ${result.refundFailures.size} payment(s) — reimbursement is incomplete:")
            result.refundFailures.forEach { (intent, reason) ->
                println("  ! ${intent.id}: $reason")
            }
        }
        if (result.indeterminateFailure != null) {
            val (intent, reason) = result.indeterminateFailure
            println("Refund outcome indeterminate for intent ${intent.id} — reconciliation required:")
            println("  ? $reason")
        }
        if (result.quote.feeAmount > 0.0 && result.quote.hasPayments) {
            println("Cancellation fee retained: $%.2f".format(result.quote.feeAmount))
        }

        val defaultReceiptPath = "receipt-${id.take(8)}.txt"
        print("\nSave refund receipt to file? (y/N): ")
        if (scanner.nextLine().trim().equals("y", ignoreCase = true)) {
            print("Receipt file path (blank for $defaultReceiptPath): ")
            val pathInput = scanner.nextLine().trim()
            val path = pathInput.ifEmpty { defaultReceiptPath }
            val receipt = receipts.save(booking, result, path)
            println("Receipt ${receipt.receiptNumber} saved to $path")
            receipts.appendToRegister(receipt, config.defaultRefundRegisterPath)
        }

        promoteWaitlistIfAny()
    }

    private fun autoRefundForBooking(bookingId: String, booking: Booking? = service.findBooking(bookingId)) {
        val result = payments.refundAllForBooking(bookingId)
        if (result.refunded.isNotEmpty()) {
            println("Auto-refunded ${result.refunded.size} payment(s):")
            result.refunded.forEach { println("  ↩ $it") }
            if (booking != null) {
                result.refunded.forEach {
                    notifications.dispatch(NotificationEvent.PaymentRefunded(it, booking))
                }
            }
        }
        if (result.failures.isNotEmpty()) {
            println("Refund failed for ${result.failures.size} payment(s):")
            result.failures.forEach { (intent, reason) ->
                println("  ! ${intent.id}: $reason")
            }
        }
        if (result.indeterminateFailure != null) {
            val (intent, reason) = result.indeterminateFailure
            println("Refund outcome indeterminate for intent ${intent.id} — reconciliation required:")
            println("  ? $reason")
        }
    }

    private fun promoteWaitlistIfAny() {
        val promoted = waitlist.tryPromoteAll()
        if (promoted.isNotEmpty()) {
            println("Promoted ${promoted.size} from waitlist:")
            promoted.forEach { promotion ->
                println("  + ${promotion.booking}")
                notifications.dispatch(
                    NotificationEvent.WaitlistPromoted(promotion.entry, promotion.booking)
                )
            }
        }
    }

    // ── 5. Search by customer ──────────────────────────────────────

    private fun searchByCustomer() {
        print("Customer name to search: ")
        val name = scanner.nextLine().trim()
        if (name.isEmpty()) { println("Search term cannot be empty."); return }
        val results = service.searchByCustomer(name)
        if (results.isEmpty()) {
            println("No bookings found for \"$name\".")
        } else {
            println("Found ${results.size} booking(s):")
            results.forEach(::println)
        }
    }

    // ── 6. Update / reschedule ─────────────────────────────────────

    private fun updateBooking() {
        print("Booking ID to update: ")
        val id = scanner.nextLine().trim()

        print("New date (YYYY-MM-DD, leave blank to keep): ")
        val dateInput = scanner.nextLine().trim()
        var newDate: LocalDate? = null
        if (dateInput.isNotEmpty()) {
            try {
                newDate = LocalDate.parse(dateInput)
            } catch (e: DateTimeParseException) {
                println("Invalid date format."); return
            }
        }

        print("New start time (HH:MM, leave blank to keep): ")
        val timeInput = scanner.nextLine().trim()
        var newStartTime: LocalTime? = null
        if (timeInput.isNotEmpty()) {
            try {
                newStartTime = LocalTime.parse(timeInput)
            } catch (e: DateTimeParseException) {
                println("Invalid time format."); return
            }
        }

        print("New duration minutes (leave blank to keep): ")
        val durationInput = scanner.nextLine().trim()
        var newDuration: Int? = null
        if (durationInput.isNotEmpty()) {
            newDuration = durationInput.toIntOrNull()
            if (newDuration == null || newDuration <= 0) {
                println("Duration must be a positive integer."); return
            }
        }

        val result = validator.validateUpdate(id, newDate, newStartTime, newDuration)
        if (!result.valid) {
            println("Validation failed:")
            result.errors.forEach { println("  - $it") }
            return
        }

        print("New description (leave blank to keep): ")
        val newDescription = scanner.nextLine().trim()

        try {
            val updated = service.updateBooking(id, newDate, newStartTime, newDuration, newDescription)
            println("Booking updated: $updated")
        } catch (e: Exception) {
            println("Error: ${e.message}")
        }
    }

    // ── 7. Statistics ──────────────────────────────────────────────

    private fun showStatistics() {
        val basic = service.getStatistics()
        println("--- Booking Statistics ---")
        println("Total:     ${basic["total"]}")
        println("Confirmed: ${basic["confirmed"]}")
        println("Cancelled: ${basic["cancelled"]}")
        println("Capacity:  ${service.capacity}")
        println("Quoted revenue: $%.2f".format(service.totalQuotedRevenue()))

        println("\n--- Activity ---")
        val busiest = stats.busiestDate()
        if (busiest == null) {
            println("Busiest day:           (no bookings yet)")
        } else {
            println("Busiest day:           ${busiest.date} (${busiest.count} booking(s))")
        }
        println("Avg bookings / day:    %.2f".format(stats.averageBookingsPerActiveDay()))
        println("Peak utilisation:      %.1f%%".format(stats.peakCapacityUtilisation()))
        println("Booking horizon:       ${stats.bookingHorizonDays()} day(s)")

        val top = stats.topCustomers(3)
        if (top.isNotEmpty()) {
            println("\nTop customers:")
            top.forEachIndexed { i, c -> println("  ${i + 1}) ${c.customer} — ${c.count}") }
        }

        val perResource = stats.peakUtilisationByResource()
        if (perResource.size > 1) {
            println("\nPeak utilisation by resource:")
            perResource.forEach { r ->
                println("  %-20s %5.1f%%".format(r.resourceName, r.percent))
            }
        }
    }

    // ── 8. Export to CSV ───────────────────────────────────────────

    private fun exportToCsv() {
        print("File path (default: ${config.defaultCsvPath}): ")
        var path = scanner.nextLine().trim()
        if (path.isEmpty()) path = config.defaultCsvPath
        try {
            service.exportToCsv(path)
            println("Bookings exported to $path")
        } catch (e: IOException) {
            println("Export failed: ${e.message}")
        }
    }

    // ── 9. Generate report ─────────────────────────────────────────

    private fun generateReport() {
        println("Report type:")
        println("  a) Summary report")
        println("  b) Daily schedule")
        println("  c) Customer report")
        print("Choice: ")
        val type = scanner.nextLine().trim().lowercase()

        val report: String = when (type) {
            "a" -> reportGenerator.generateSummaryReport()
            "b" -> {
                print("From date (YYYY-MM-DD): ")
                val from: LocalDate
                try {
                    from = LocalDate.parse(scanner.nextLine().trim())
                } catch (e: DateTimeParseException) {
                    println("Invalid date format."); return
                }
                print("To date (YYYY-MM-DD): ")
                val to: LocalDate
                try {
                    to = LocalDate.parse(scanner.nextLine().trim())
                } catch (e: DateTimeParseException) {
                    println("Invalid date format."); return
                }
                try {
                    reportGenerator.generateDailySchedule(from, to)
                } catch (e: IllegalArgumentException) {
                    println("Error: ${e.message}"); return
                }
            }
            "c" -> {
                print("Customer name: ")
                val name = scanner.nextLine().trim()
                if (name.isEmpty()) { println("Name cannot be empty."); return }
                reportGenerator.generateCustomerReport(name)
            }
            else -> { println("Invalid report type."); return }
        }

        println("\n$report")

        print("Save to file? (y/n): ")
        if (scanner.nextLine().trim().equals("y", ignoreCase = true)) {
            print("File path: ")
            val path = scanner.nextLine().trim()
            try {
                reportGenerator.saveToFile(report, path)
                println("Report saved to $path")
            } catch (e: IOException) {
                println("Save failed: ${e.message}")
            }
        }
    }

    // ── 10. Advanced search / filter ───────────────────────────────

    private fun advancedSearch() {
        val filter = BookingFilter(service.listBookings())

        print("Filter by status? (CONFIRMED/CANCELLED/blank for all): ")
        val statusInput = scanner.nextLine().trim().uppercase()
        if (statusInput.isNotEmpty()) {
            try {
                filter.byStatus(Booking.Status.valueOf(statusInput))
            } catch (e: IllegalArgumentException) {
                println("Invalid status, showing all.")
            }
        }

        print("From date? (YYYY-MM-DD or blank): ")
        val fromInput = scanner.nextLine().trim()
        if (fromInput.isNotEmpty()) {
            try {
                filter.fromDate(LocalDate.parse(fromInput))
            } catch (e: DateTimeParseException) {
                println("Invalid date, skipping from-date filter.")
            }
        }

        print("To date? (YYYY-MM-DD or blank): ")
        val toInput = scanner.nextLine().trim()
        if (toInput.isNotEmpty()) {
            try {
                filter.toDate(LocalDate.parse(toInput))
            } catch (e: DateTimeParseException) {
                println("Invalid date, skipping to-date filter.")
            }
        }

        print("Customer name contains? (blank for all): ")
        val customerInput = scanner.nextLine().trim()
        if (customerInput.isNotEmpty()) {
            filter.byCustomer(customerInput)
        }

        print("Tag (must contain, blank to skip): ")
        val tagInput = scanner.nextLine().trim()
        if (tagInput.isNotEmpty()) {
            filter.byTag(tagInput)
        }

        print("Internal reference contains? (blank to skip): ")
        val refInput = scanner.nextLine().trim()
        if (refInput.isNotEmpty()) {
            filter.byInternalReference(refInput)
        }

        print("Sort by? (date/customer/status, default date): ")
        val sortInput = scanner.nextLine().trim().lowercase()
        val sortField = when (sortInput) {
            "customer" -> BookingFilter.SortField.CUSTOMER_NAME
            "status"   -> BookingFilter.SortField.STATUS
            "duration" -> BookingFilter.SortField.DURATION
            "price"    -> BookingFilter.SortField.PRICE
            else       -> BookingFilter.SortField.DATE
        }

        print("Order? (asc/desc, default asc): ")
        val ascending = !scanner.nextLine().trim().equals("desc", ignoreCase = true)

        filter.sortBy(sortField, ascending)

        print("Limit results? (number or blank for all): ")
        val limitInput = scanner.nextLine().trim()
        if (limitInput.isNotEmpty()) {
            try {
                filter.limit(limitInput.toInt())
            } catch (e: NumberFormatException) {
                println("Invalid number, showing all.")
            }
        }

        println(filter.formatResults())
    }

    // ── 11. View audit log ─────────────────────────────────────────

    private fun viewAuditLog() {
        val auditLog = service.auditLog
        val entries = auditLog.getAll()

        if (entries.isEmpty()) { println("Audit log is empty."); return }

        entries.forEach(::println)
        println("\n${auditLog.summary()}")
    }

    // ── 12. Booking history ────────────────────────────────────────

    private fun viewBookingHistory() {
        print("Booking ID: ")
        val id = scanner.nextLine().trim()

        val history = service.auditLog.getByBookingId(id)
        if (history.isEmpty()) {
            println("No history found for booking $id.")
        } else {
            println("History for booking $id:")
            history.forEach(::println)
        }
    }

    // ── 13. Quote price ────────────────────────────────────────────

    private fun quotePrice() {
        print("Booking ID: ")
        val id = scanner.nextLine().trim()
        print("Customer type (REGULAR/VIP/CORPORATE): ")
        val type = scanner.nextLine().trim()
        print("Party size: ")
        val party = scanner.nextLine().trim().toIntOrNull()
        if (party == null || party <= 0) {
            println("Error: Party size must be a positive integer.")
            return
        }
        // If the booking is linked to a customer record, prefer that record's
        // loyalty years and let the operator skip the prompt by hitting enter.
        val linkedLoyalty = pricer.resolveLoyaltyYears(id, fallback = -1)
            .takeIf { it >= 0 }
        if (linkedLoyalty != null) {
            print("Loyalty years (linked customer has $linkedLoyalty, blank to accept): ")
        } else {
            print("Loyalty years: ")
        }
        val loyaltyInput = scanner.nextLine().trim()
        val loyalty = if (loyaltyInput.isEmpty() && linkedLoyalty != null) linkedLoyalty
                      else loyaltyInput.toIntOrNull()
        if (loyalty == null || loyalty < 0) {
            println("Error: Loyalty years must be a non-negative integer.")
            return
        }
        print("Coupon code (blank for none): ")
        val coupon = scanner.nextLine().trim().ifEmpty { null }
        print("Prepay? (y/n): ")
        val prepay = scanner.nextLine().trim().equals("y", ignoreCase = true)
        print("Season (HIGH/LOW/MID): ")
        val season = scanner.nextLine().trim()
        print("Save quote to file? (path or blank): ")
        val saveTo = scanner.nextLine().trim().ifEmpty { null }

        try {
            pricer.calculateAndPrintAndMaybeSave(
                id, type, party, loyalty, coupon, prepay, season, saveTo
            )
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
        }
    }

    // ── 14. Set capacity ───────────────────────────────────────────

    private fun setCapacity() {
        print("New capacity (positive integer, current ${service.capacity}): ")
        val value = scanner.nextLine().trim().toIntOrNull()
        if (value == null) {
            println("Capacity must be an integer."); return
        }
        try {
            service.capacity = value
            println("Capacity set to ${service.capacity}.")
            // A larger capacity may now allow waitlisted entries through.
            promoteWaitlistIfAny()
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
        }
    }

    // ── 15. Create recurring series ────────────────────────────────

    private fun createRecurringSeries() {
        print("Customer name: ")
        val name = scanner.nextLine().trim()

        print("First date (YYYY-MM-DD): ")
        val first: LocalDate
        try {
            first = LocalDate.parse(scanner.nextLine().trim())
        } catch (e: DateTimeParseException) {
            println("Invalid date format."); return
        }

        print("Start time (HH:MM, 24h): ")
        val startTime: LocalTime
        try {
            startTime = LocalTime.parse(scanner.nextLine().trim())
        } catch (e: DateTimeParseException) {
            println("Invalid time format."); return
        }

        print("Duration in minutes: ")
        val duration = scanner.nextLine().trim().toIntOrNull()
        if (duration == null || duration <= 0) {
            println("Duration must be a positive integer."); return
        }

        print("Description: ")
        val description = scanner.nextLine().trim()

        print("Cadence (DAILY/WEEKLY/BIWEEKLY/MONTHLY/QUARTERLY/ANNUAL): ")
        val cadence: RecurringBookingService.Cadence = try {
            RecurringBookingService.Cadence.valueOf(scanner.nextLine().trim().uppercase())
        } catch (e: IllegalArgumentException) {
            println("Invalid cadence."); return
        }

        print("Number of occurrences: ")
        val count = scanner.nextLine().trim().toIntOrNull()
        if (count == null || count < 1) {
            println("Count must be a positive integer."); return
        }

        val r = try {
            recurring.createSeries(name, first, startTime, duration, description, cadence, count)
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}"); return
        }

        println("\nSeries ${r.seriesId}: ${r.created.size} created, ${r.skipped.size} skipped.")
        r.created.forEach { println("  + $it") }
        if (r.skipped.isNotEmpty()) {
            println("Skipped:")
            r.skipped.forEach { s ->
                println("  - ${s.date}: ${s.reasons.joinToString("; ")}")
            }
        }
    }

    // ── 16. Cancel recurring series ────────────────────────────────

    private fun cancelRecurringSeries() {
        print("Series ID: ")
        val seriesId = scanner.nextLine().trim()
        if (seriesId.isEmpty()) { println("Series ID cannot be empty."); return }

        val matching = service.findBySeries(seriesId)
        if (matching.isEmpty()) { println("No bookings found for series $seriesId."); return }

        // Snapshot the IDs that were CONFIRMED before the bulk cancel — these
        // are the bookings we'll need to refund. Reading after the fact would
        // include long-cancelled ones too.
        val toRefund = matching.filter { it.status == Booking.Status.CONFIRMED }.map { it.id }

        val cancelled = recurring.cancelSeries(seriesId)
        println("Cancelled $cancelled booking(s) in series $seriesId.")
        toRefund.forEach { autoRefundForBooking(it) }
        promoteWaitlistIfAny()
    }

    // ── 17. View waitlist ──────────────────────────────────────────

    private fun viewWaitlist() {
        val entries = waitlist.list()
        if (entries.isEmpty()) { println("Waitlist is empty."); return }
        println("Waitlist (${entries.size}):")
        entries.forEachIndexed { i, e -> println("  ${i + 1}. $e") }
    }

    // ── 18. Remove from waitlist ───────────────────────────────────

    private fun removeFromWaitlist() {
        print("Waitlist entry ID: ")
        val id = scanner.nextLine().trim()
        if (waitlist.remove(id)) println("Removed waitlist entry $id.")
        else println("Waitlist entry not found.")
    }

    // ── 19. Create payment intent ──────────────────────────────────

    private fun createPaymentIntent() {
        print("Booking ID: ")
        val id = scanner.nextLine().trim()
        print("Currency (3-letter ISO, blank for USD): ")
        val currencyInput = scanner.nextLine().trim()
        val currency = if (currencyInput.isEmpty()) "USD" else currencyInput
        try {
            val intent = payments.createIntent(id, currency)
            println("Payment intent created: $intent")
            println("Use option 20 with intent id ${intent.id} to confirm.")
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
        } catch (e: IllegalStateException) {
            println("Error: ${e.message}")
        }
    }

    // ── 20. Confirm payment ────────────────────────────────────────

    private fun confirmPayment() {
        print("Payment intent ID: ")
        val id = scanner.nextLine().trim()
        try {
            val intent = payments.confirm(id)
            println("Payment $id is now ${intent.status}: $intent")
            val booking = service.findBooking(intent.bookingId)
            if (booking != null) {
                val event = when (intent.status) {
                    com.booking.model.PaymentIntent.Status.SUCCEEDED ->
                        NotificationEvent.PaymentSucceeded(intent, booking)
                    com.booking.model.PaymentIntent.Status.FAILED ->
                        NotificationEvent.PaymentFailed(intent, booking)
                    else -> null
                }
                event?.let { notifications.dispatch(it) }
            }
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
        } catch (e: IllegalStateException) {
            println("Error: ${e.message}")
        }
    }

    // ── 21. Refund payment ─────────────────────────────────────────

    private fun refundPayment() {
        print("Payment intent ID: ")
        val id = scanner.nextLine().trim()
        try {
            val intent = payments.refund(id)
            println("Payment $id refunded: $intent")
            val booking = service.findBooking(intent.bookingId)
            if (booking != null) {
                notifications.dispatch(NotificationEvent.PaymentRefunded(intent, booking))
            }
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
        } catch (e: IllegalStateException) {
            println("Error: ${e.message}")
        }
    }

    // ── 22. List payments ──────────────────────────────────────────

    private fun listPayments() {
        val all = payments.list()
        if (all.isEmpty()) { println("No payment intents."); return }
        all.forEach(::println)
        println("Net settled: $%.2f".format(payments.netSettled()))
    }

    // ── 23. Export to iCalendar ────────────────────────────────────

    private fun exportICal() {
        println("Export:")
        println("  a) Single booking")
        println("  b) All bookings to one calendar")
        print("Choice: ")
        val choice = scanner.nextLine().trim().lowercase()
        when (choice) {
            "a" -> {
                print("Booking ID: ")
                val id = scanner.nextLine().trim()
                val booking = service.findBooking(id)
                if (booking == null) { println("Booking not found."); return }
                print("File path (default: booking.ics): ")
                val path = scanner.nextLine().trim().ifEmpty { "booking.ics" }
                try {
                    ical.saveBooking(booking, path)
                    println("Wrote $path")
                } catch (e: IOException) {
                    println("Export failed: ${e.message}")
                }
            }
            "b" -> {
                if (service.listBookings().isEmpty()) { println("No bookings to export."); return }
                print("File path (default: ${config.defaultIcsPath}): ")
                val path = scanner.nextLine().trim().ifEmpty { config.defaultIcsPath }
                try {
                    ical.saveAll(path)
                    println("Wrote ${service.listBookings().size} event(s) to $path")
                } catch (e: IOException) {
                    println("Export failed: ${e.message}")
                }
            }
            else -> println("Invalid choice.")
        }
    }

    // ── 25. Manage customer notification preferences ──────────────

    private fun manageCustomerPreferences() {
        print("Customer name (or blank to list known): ")
        val name = scanner.nextLine().trim()
        if (name.isEmpty()) {
            val known = notifications.prefs.knownCustomers()
            if (known.isEmpty()) println("(no customer-specific preferences set)")
            else known.sorted().forEach { println("  - $it") }
            return
        }

        // Show current state for this customer.
        val mutedChannels = notifications.prefs.mutedChannels(name)
        val mutedEvents = notifications.prefs.mutedEvents(name)
        println("Current preferences for $name:")
        if (mutedChannels.isEmpty() && mutedEvents.isEmpty()) println("  (default — receives everything)")
        else {
            mutedChannels.forEach { println("  channel '$it' muted") }
            mutedEvents.forEach { (ch, et) -> println("  channel '$ch' / event $et muted") }
        }

        println("""
            Actions:
              a) Mute a whole channel
              b) Unmute a whole channel
              c) Mute a specific (channel, event-type)
              d) Unmute a specific (channel, event-type)
              e) Clear all rules for this customer
              (blank) cancel
        """.trimIndent())
        print("Choice: ")
        when (scanner.nextLine().trim().lowercase()) {
            "a" -> notifications.prefs.muteChannel(name, askChannel() ?: return)
                .also { println("Channel muted for $name.") }
            "b" -> notifications.prefs.unmuteChannel(name, askChannel() ?: return)
                .also { println("Channel unmuted for $name.") }
            "c" -> {
                val ch = askChannel() ?: return
                val et = askEventType() ?: return
                notifications.prefs.muteEvent(name, ch, et)
                println("Channel '$ch' / event $et muted for $name.")
            }
            "d" -> {
                val ch = askChannel() ?: return
                val et = askEventType() ?: return
                notifications.prefs.unmuteEvent(name, ch, et)
                println("Channel '$ch' / event $et unmuted for $name.")
            }
            "e" -> {
                notifications.prefs.clear(name)
                println("All preferences cleared for $name.")
            }
            else -> println("Cancelled.")
        }
    }

    private fun askChannel(): String? {
        val channels = notifications.channelStates().map { it.first }
        print("Channel (${channels.joinToString("/")}): ")
        val ch = scanner.nextLine().trim().lowercase()
        if (ch !in channels) {
            println("Unknown channel '$ch'."); return null
        }
        return ch
    }

    private fun askEventType(): NotificationPreferences.EventType? {
        val types = NotificationPreferences.EventType.values()
        println("Event types:")
        types.forEachIndexed { i, t -> println("  ${i + 1}) $t") }
        print("Pick (number): ")
        val idx = scanner.nextLine().trim().toIntOrNull()
        if (idx == null || idx !in 1..types.size) {
            println("Invalid event type."); return null
        }
        return types[idx - 1]
    }

    // ── 24. Manage notification channels ──────────────────────────

    private fun manageNotificationChannels() {
        println("Notification channels:")
        val states = notifications.channelStates()
        states.forEachIndexed { i, (name, enabled) ->
            val flag = if (enabled) "✓" else " "
            println("  ${i + 1}) [$flag] $name")
        }
        print("Toggle which? (number, blank to cancel): ")
        val input = scanner.nextLine().trim()
        if (input.isEmpty()) return
        val idx = input.toIntOrNull()
        if (idx == null || idx !in 1..states.size) {
            println("Invalid selection."); return
        }
        val (name, wasEnabled) = states[idx - 1]
        notifications.setEnabled(name, !wasEnabled)
        println("Channel '$name' is now ${if (!wasEnabled) "ENABLED" else "DISABLED"}.")
    }

    // ── 26. Coupon management ──────────────────────────────────────

    private fun manageCoupons() {
        println("""
            Coupons:
              a) List
              b) Create flat-amount coupon
              c) Create percent coupon
              d) Delete
              (blank) cancel
        """.trimIndent())
        print("Choice: ")
        when (scanner.nextLine().trim().lowercase()) {
            "a" -> listCoupons()
            "b" -> createCoupon(percent = false)
            "c" -> createCoupon(percent = true)
            "d" -> deleteCoupon()
            "" -> {}
            else -> println("Invalid choice.")
        }
    }

    private fun listCoupons() {
        val all = pricer.couponRegistry.list()
        if (all.isEmpty()) { println("No coupons registered."); return }
        all.forEach(::println)
    }

    private fun createCoupon(percent: Boolean) {
        print("Code: ")
        val code = scanner.nextLine().trim()
        if (code.isEmpty()) { println("Code cannot be empty."); return }

        val discount = if (percent) {
            print("Percent off (1-100): ")
            val pct = scanner.nextLine().trim().toIntOrNull() ?: run {
                println("Must be a number."); return
            }
            try {
                com.booking.model.CouponDiscount.Percent(pct)
            } catch (e: IllegalArgumentException) {
                println("Error: ${e.message}"); return
            }
        } else {
            print("Flat amount off: ")
            val amount = scanner.nextLine().trim().toDoubleOrNull() ?: run {
                println("Must be a number."); return
            }
            try {
                com.booking.model.CouponDiscount.Flat(amount)
            } catch (e: IllegalArgumentException) {
                println("Error: ${e.message}"); return
            }
        }

        print("Valid from (YYYY-MM-DD, blank for none): ")
        val fromInput = scanner.nextLine().trim()
        val validFrom = if (fromInput.isEmpty()) null else try {
            LocalDate.parse(fromInput)
        } catch (e: DateTimeParseException) {
            println("Invalid date."); return
        }

        print("Valid until (YYYY-MM-DD, blank for none): ")
        val untilInput = scanner.nextLine().trim()
        val validUntil = if (untilInput.isEmpty()) null else try {
            LocalDate.parse(untilInput)
        } catch (e: DateTimeParseException) {
            println("Invalid date."); return
        }

        print("Max uses (blank for unlimited): ")
        val maxUsesInput = scanner.nextLine().trim()
        val maxUses = if (maxUsesInput.isEmpty()) null else maxUsesInput.toIntOrNull() ?: run {
            println("Must be a number."); return
        }

        print("Restrict to customer type (REGULAR/VIP/CORPORATE, blank for any): ")
        val type = scanner.nextLine().trim().ifEmpty { null }

        try {
            val coupon = com.booking.model.Coupon(
                code, discount, validFrom, validUntil, maxUses, type
            )
            pricer.couponRegistry.register(coupon)
            println("Registered: $coupon")
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
        }
    }

    private fun deleteCoupon() {
        print("Code: ")
        val code = scanner.nextLine().trim()
        if (pricer.couponRegistry.delete(code)) println("Deleted.") else println("Unknown coupon.")
    }

    // ── 27. Save snapshot ─────────────────────────────────────────

    private fun saveSnapshot() {
        print("File path (default: snapshot.json): ")
        val path = scanner.nextLine().trim().ifEmpty { "snapshot.json" }
        try {
            snapshots.save(path)
            println("Saved to $path")
        } catch (e: Exception) {
            println("Save failed: ${e.message}")
        }
    }

    // ── 28. Load snapshot ─────────────────────────────────────────

    private fun loadSnapshot() {
        print("File path (default: snapshot.json): ")
        val path = scanner.nextLine().trim().ifEmpty { "snapshot.json" }
        println("⚠ Loading replaces all in-memory state.")
        print("Continue? (y/N): ")
        if (!scanner.nextLine().trim().equals("y", ignoreCase = true)) {
            println("Cancelled.")
            return
        }
        try {
            snapshots.load(path)
            println("Loaded from $path: ${service.listBookings().size} booking(s), " +
                "${customers.size()} customer(s), ${service.resources.size()} resource(s)")
        } catch (e: Exception) {
            println("Load failed: ${e.message}")
        }
    }

    /**
     * Ask the operator which resource the booking should live on.
     *
     * Returns the chosen resource id, or null to let the booking land on
     * the system default. Skips the prompt entirely when only one resource
     * is registered (the default), since the answer is forced.
     */
    private fun promptForResource(): String? {
        val all = service.resources.list()
        if (all.size <= 1) return null
        println("Resources:")
        all.forEachIndexed { i, r -> println("  ${i + 1}) $r") }
        print("Pick a resource (number, blank for default): ")
        val input = scanner.nextLine().trim()
        if (input.isEmpty()) return null
        val idx = input.toIntOrNull()
        if (idx == null || idx !in 1..all.size) {
            println("Invalid selection, using default resource.")
            return null
        }
        return all[idx - 1].id
    }
}
