package com.booking.service

import com.booking.model.Booking
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDate

/**
 * Rich text report generator producing summary, daily schedule, and per-customer reports.
 *
 * Reports can be printed to console or saved to a `.txt` file via [saveToFile].
 *
 * [staff] is optional so callers that don't use staff scheduling aren't
 * forced to wire one up; when absent, the staff workload section is
 * simply omitted from the summary report and [generateStaffSchedule]
 * throws rather than silently returning an empty report.
 */
class ReportGenerator(private val service: BookingService, private val staff: StaffService? = null) {

    // ── Summary Report ───────────────────────────────────────────

    fun generateSummaryReport(): String {
        val all = service.listBookings()
        val stats = service.getStatistics()
        val sb = StringBuilder()

        sb.appendLine("===================================")
        sb.appendLine("       BOOKING SUMMARY REPORT      ")
        sb.appendLine("===================================")
        sb.appendLine("Generated: ${LocalDate.now()}\n")

        sb.appendLine("-- Overview --")
        sb.appendLine("  Total bookings:     ${stats["total"]}")
        sb.appendLine("  Confirmed:          ${stats["confirmed"]}")
        sb.appendLine("  Cancelled:          ${stats["cancelled"]}")
        sb.appendLine("  Capacity:           ${service.capacity}")
        sb.appendLine("  Quoted revenue:     $%.2f".format(service.totalQuotedRevenue()))

        sb.appendLine("\n-- Top Customers --")
        all.filter { it.status == Booking.Status.CONFIRMED }
            .groupingBy { it.customerName }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .forEach { (name, count) ->
                sb.appendLine("  %-20s %d booking(s)".format(name, count))
            }

        sb.appendLine("\n-- Busiest Dates --")
        all.filter { it.status == Booking.Status.CONFIRMED }
            .groupingBy { it.date }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .forEach { (date, count) ->
                sb.appendLine("  $date    $count booking(s)")
            }

        sb.appendLine("\n-- Tag Usage --")
        val tagCounts = all
            .filter { it.status == Booking.Status.CONFIRMED }
            .flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
        if (tagCounts.isEmpty()) {
            sb.appendLine("  (no tags in use)")
        } else {
            tagCounts.entries
                .sortedByDescending { it.value }
                .take(10)
                .forEach { (tag, count) ->
                    sb.appendLine("  %-20s %d booking(s)".format(tag, count))
                }
        }

        sb.appendLine("\n-- Upcoming (next 7 days) --")
        val today = LocalDate.now()
        val weekOut = today.plusDays(7)
        val upcoming = all
            .filter { it.status == Booking.Status.CONFIRMED }
            .filter { !it.date.isBefore(today) && it.date.isBefore(weekOut) }
            .sortedBy { it.date }
        if (upcoming.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            upcoming.forEach { sb.appendLine("  $it") }
        }

        staff?.let { st ->
            sb.appendLine("\n-- Staff Workload --")
            val workload = st.workload().filter { it.confirmedBookings > 0 }
            if (workload.isEmpty()) {
                sb.appendLine("  (no staff-assigned bookings)")
            } else {
                workload.take(10).forEach { w ->
                    sb.appendLine("  %-20s %d booking(s), %d minute(s)".format(w.staffName, w.confirmedBookings, w.bookedMinutes))
                }
            }
        }

        return sb.toString()
    }

    // ── Staff Schedule Report ─────────────────────────────────────

    /**
     * One day's shifts and the bookings assigned within each, so an
     * operator can see at a glance who's working and what they're
     * booked for. Throws [IllegalStateException] if no [StaffService]
     * was wired in.
     */
    fun generateStaffSchedule(date: LocalDate): String {
        val st = staff ?: error("ReportGenerator was constructed without a StaffService.")
        val sb = StringBuilder()

        sb.appendLine("===================================")
        sb.appendLine("       STAFF SCHEDULE REPORT       ")
        sb.appendLine("       $date")
        sb.appendLine("===================================\n")

        val shiftsToday = st.shiftsOn(date)
        if (shiftsToday.isEmpty()) {
            sb.appendLine("(no shifts scheduled)")
            return sb.toString()
        }

        val bookingsToday = service.listBookings()
            .filter { it.status == Booking.Status.CONFIRMED }
            .filter { it.date == date }
            .filter { it.staffId != null }
            .groupBy { it.staffId }

        shiftsToday.forEach { shift ->
            val member = st.find(shift.staffId)
            val name = member?.name ?: "(unknown staff ${shift.staffId})"
            sb.appendLine("-- $name: ${shift.startTime}-${shift.endTime} --")
            val assigned = (bookingsToday[shift.staffId] ?: emptyList()).sortedBy { it.startTime }
            if (assigned.isEmpty()) {
                sb.appendLine("  (no bookings assigned)")
            } else {
                assigned.forEach { sb.appendLine("  $it") }
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    // ── Daily Schedule Report ────────────────────────────────────

    fun generateDailySchedule(from: LocalDate, to: LocalDate): String {
        require(!from.isAfter(to)) { "Invalid date range: from must be on or before to." }

        val sb = StringBuilder()

        sb.appendLine("===================================")
        sb.appendLine("       DAILY SCHEDULE REPORT       ")
        sb.appendLine("       $from to $to")
        sb.appendLine("===================================\n")

        val byDate = service.listBookings()
            .filter { it.status == Booking.Status.CONFIRMED }
            .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
            .sortedBy { it.date }
            .groupBy { it.date }

        var cursor = from
        while (!cursor.isAfter(to)) {
            sb.appendLine("-- $cursor (${cursor.dayOfWeek}) --")
            val dayBookings = byDate[cursor] ?: emptyList()
            if (dayBookings.isEmpty()) {
                sb.appendLine("  (no bookings)")
            } else {
                dayBookings.forEach { sb.appendLine("  $it") }
            }
            sb.appendLine()
            cursor = cursor.plusDays(1)
        }

        return sb.toString()
    }

    // ── Customer Report ──────────────────────────────────────────

    fun generateCustomerReport(customerName: String): String {
        val customerBookings = service.searchByCustomer(customerName)
        val sb = StringBuilder()

        sb.appendLine("===================================")
        sb.appendLine("         CUSTOMER REPORT           ")
        sb.appendLine("         Customer: $customerName")
        sb.appendLine("===================================\n")

        if (customerBookings.isEmpty()) {
            sb.appendLine("No bookings found for this customer.")
            return sb.toString()
        }

        val confirmed = customerBookings.count { it.status == Booking.Status.CONFIRMED }
        val cancelled = customerBookings.count { it.status == Booking.Status.CANCELLED }

        sb.appendLine("Total: ${customerBookings.size}  |  Confirmed: $confirmed  |  Cancelled: $cancelled\n")

        customerBookings
            .sortedBy { it.date }
            .forEach { b ->
                sb.appendLine("  $b")
                // Internal notes are staff-facing only and intentionally NOT
                // exported via iCal; the customer report is the right surface
                // for them.
                b.notes?.takeIf { it.isNotBlank() }?.let {
                    sb.appendLine("      notes: $it")
                }
            }

        return sb.toString()
    }

    // ── Save report to file ──────────────────────────────────────

    fun saveToFile(report: String, filePath: String) {
        PrintWriter(FileWriter(filePath)).use { it.print(report) }
    }
}
