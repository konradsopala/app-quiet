package com.booking

import com.booking.model.Booking
import com.booking.service.AuditLog
import com.booking.service.BookingPricer
import com.booking.service.BookingService
import com.booking.service.BookingValidator
import com.booking.service.ICalExporter
import com.booking.service.MockPaymentProcessor
import com.booking.service.PaymentService
import com.booking.service.RecurringBookingService
import com.booking.service.ReportGenerator
import com.booking.service.WaitlistService
import com.booking.util.BookingFilter
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.Scanner

fun main() {
    App().run()
}

class App {

    private val service = BookingService()
    private val validator = BookingValidator(service)
    private val reportGenerator = ReportGenerator(service)
    private val pricer = BookingPricer(service)
    private val recurring = RecurringBookingService(service, validator)
    private val waitlist = WaitlistService(service, validator)
    private val payments = PaymentService(service, MockPaymentProcessor())
    private val ical = ICalExporter(service)
    private val scanner = Scanner(System.`in`)

    fun run() {
        println("=== Booking Manager v2 ===")

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
                |24) Exit
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
                "24" -> { println("Goodbye!"); return }
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

        val result = validator.validateNewBooking(name, date, startTime, duration, description)
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

        try {
            val booking = service.createBooking(name, date, startTime, duration, description)
            println("Booking created: $booking")
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
        }
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
        if (service.cancelBooking(id)) {
            println("Booking cancelled.")
            autoRefundForBooking(id)
            promoteWaitlistIfAny()
        } else {
            println("Booking not found or already cancelled.")
        }
    }

    private fun autoRefundForBooking(bookingId: String) {
        val result = payments.refundAllForBooking(bookingId)
        if (result.refunded.isNotEmpty()) {
            println("Auto-refunded ${result.refunded.size} payment(s):")
            result.refunded.forEach { println("  ↩ $it") }
        }
        if (result.failures.isNotEmpty()) {
            println("Refund failed for ${result.failures.size} payment(s):")
            result.failures.forEach { (intent, reason) ->
                println("  ! ${intent.id}: $reason")
            }
        }
    }

    private fun promoteWaitlistIfAny() {
        val promoted = waitlist.tryPromoteAll()
        if (promoted.isNotEmpty()) {
            println("Promoted ${promoted.size} from waitlist:")
            promoted.forEach { println("  + $it") }
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
        val stats = service.getStatistics()
        println("--- Booking Statistics ---")
        println("Total:     ${stats["total"]}")
        println("Confirmed: ${stats["confirmed"]}")
        println("Cancelled: ${stats["cancelled"]}")
        println("Capacity:  ${service.capacity}")
        println("Quoted revenue: $%.2f".format(service.totalQuotedRevenue()))
    }

    // ── 8. Export to CSV ───────────────────────────────────────────

    private fun exportToCsv() {
        print("File path (default: bookings.csv): ")
        var path = scanner.nextLine().trim()
        if (path.isEmpty()) path = "bookings.csv"
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

        print("Sort by? (date/customer/status, default date): ")
        val sortInput = scanner.nextLine().trim().lowercase()
        val sortField = when (sortInput) {
            "customer" -> BookingFilter.SortField.CUSTOMER_NAME
            "status"   -> BookingFilter.SortField.STATUS
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
        print("Loyalty years: ")
        val loyalty = scanner.nextLine().trim().toIntOrNull()
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
                print("File path (default: bookings.ics): ")
                val path = scanner.nextLine().trim().ifEmpty { "bookings.ics" }
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
}
