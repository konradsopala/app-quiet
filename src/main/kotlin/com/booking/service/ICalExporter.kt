package com.booking.service

import com.booking.model.Booking
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Emits RFC 5545 (iCalendar) `.ics` content for bookings.
 *
 * Times are written as floating local time (no timezone suffix) since
 * [Booking] uses naïve `LocalDate`/`LocalTime`. Cancelled bookings get
 * `STATUS:CANCELLED` so calendar clients can grey them out instead of
 * silently dropping them.
 */
class ICalExporter(
    private val service: BookingService,
    private val productId: String = "-//Booking System//Booking Manager v2//EN",
    private val customerDirectory: CustomerService? = null
) {

    private val localFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val utcFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    private companion object {
        const val DEFAULT_ORGANIZER_EMAIL = "noreply@booking-system.local"
    }

    /** Render a single booking to .ics text. */
    fun renderBooking(booking: Booking): String {
        val sb = StringBuilder()
        appendCalendarHeader(sb)
        appendEvent(sb, booking)
        appendCalendarFooter(sb)
        return sb.toString()
    }

    /** Render all confirmed + cancelled bookings to one calendar. */
    fun renderAll(): String = renderBookings(service.listBookings())

    fun renderBookings(bookings: List<Booking>): String {
        val sb = StringBuilder()
        appendCalendarHeader(sb)
        bookings.forEach { appendEvent(sb, it) }
        appendCalendarFooter(sb)
        return sb.toString()
    }

    fun saveBooking(booking: Booking, filePath: String) {
        File(filePath).writeText(renderBooking(booking))
        service.auditLog.log(
            booking.id, AuditLog.Action.ICAL_EXPORTED,
            "Exported single event to $filePath"
        )
    }

    fun saveAll(filePath: String) {
        File(filePath).writeText(renderAll())
        service.auditLog.log(
            "SYSTEM", AuditLog.Action.ICAL_EXPORTED,
            "Exported ${service.listBookings().size} event(s) to $filePath"
        )
    }

    // ── internals ────────────────────────────────────────────────────

    private fun appendCalendarHeader(sb: StringBuilder) {
        appendLine(sb, "BEGIN:VCALENDAR")
        appendLine(sb, "VERSION:2.0")
        appendLine(sb, "PRODID:$productId")
        appendLine(sb, "CALSCALE:GREGORIAN")
        appendLine(sb, "METHOD:PUBLISH")
    }

    private fun appendCalendarFooter(sb: StringBuilder) {
        appendLine(sb, "END:VCALENDAR")
    }

    private fun appendEvent(sb: StringBuilder, b: Booking) {
        val startLocal = LocalDateTime.of(b.date, b.startTime)
        val endLocal = LocalDateTime.of(b.date, b.endTime)
        val dtstamp = LocalDateTime.now(ZoneOffset.UTC).format(utcFmt)

        appendLine(sb, "BEGIN:VEVENT")
        appendLine(sb, "UID:${b.id}@booking-system.local")
        appendLine(sb, "DTSTAMP:$dtstamp")
        appendLine(sb, "DTSTART:${startLocal.format(localFmt)}")
        appendLine(sb, "DTEND:${endLocal.format(localFmt)}")
        appendLine(sb, "SUMMARY:${escapeText(summaryFor(b))}")
        appendLine(sb, "DESCRIPTION:${escapeText(descriptionFor(b))}")
        appendLine(sb, "ORGANIZER;CN=${escapeParam(b.customerName)}:mailto:${organizerEmailFor(b)}")
        appendLine(sb, "STATUS:${if (b.status == Booking.Status.CONFIRMED) "CONFIRMED" else "CANCELLED"}")
        // LOCATION carries the human-readable resource name when the booking
        // is bound to a registered resource. Unlinked bookings (or stale
        // resource ids) skip the line rather than emit an empty one.
        locationFor(b)?.let { appendLine(sb, "LOCATION:${escapeText(it)}") }
        appendCategories(sb, b)
        // Internal reference goes out as an X- (custom) property; RFC 5545
        // §3.8.8.2 allows these and most clients ignore them silently.
        b.internalReference?.let {
            appendLine(sb, "X-BOOKING-REF:${escapeText(it)}")
        }
        appendLine(sb, "END:VEVENT")
    }

    /**
     * Emit a CATEGORIES line combining tags and the series id, if any.
     * The series tag stays prefixed with `SERIES-` so calendar clients
     * can tell free-form tags apart from system-derived ones.
     *
     * NOTE: staff-only [Booking.notes] is intentionally NOT included in
     * either DESCRIPTION or CATEGORIES — the .ics is the customer-visible
     * artefact and internal notes should not leak into it.
     */
    private fun appendCategories(sb: StringBuilder, b: Booking) {
        val parts = mutableListOf<String>()
        parts.addAll(b.tags.sorted())
        b.seriesId?.let { parts.add("SERIES-$it") }
        if (parts.isEmpty()) return
        // Each category is escaped individually; commas inside a category
        // name would otherwise be mis-parsed as separators.
        appendLine(sb, "CATEGORIES:" + parts.joinToString(",") { escapeText(it) })
    }

    /**
     * Resolve the resource's human-readable name from its id, or null
     * when the booking isn't bound to a registered resource. Stale ids
     * (resource deleted after the booking landed) are treated as
     * "no resource" rather than crashing the export.
     */
    private fun locationFor(b: Booking): String? {
        val resourceId = b.resourceId ?: return null
        return service.resources.find(resourceId)?.name
    }

    /**
     * Prefer the linked customer's email when a directory is wired in and
     * the customer has one on file; otherwise fall back to the no-reply
     * placeholder so the iCal output is still well-formed.
     */
    private fun organizerEmailFor(b: Booking): String {
        val directory = customerDirectory ?: return DEFAULT_ORGANIZER_EMAIL
        val customerId = b.customerId ?: return DEFAULT_ORGANIZER_EMAIL
        val email = directory.find(customerId)?.email ?: return DEFAULT_ORGANIZER_EMAIL
        return email
    }

    private fun summaryFor(b: Booking): String {
        val base = if (b.description.isBlank()) "Booking for ${b.customerName}"
                   else "${b.customerName}: ${b.description}"
        return base
    }

    private fun descriptionFor(b: Booking): String {
        val parts = mutableListOf("Booking ${b.id}", "Customer: ${b.customerName}")
        if (b.description.isNotBlank()) parts.add("Notes: ${b.description}")
        b.quote?.let { parts.add("Quoted total: $%.2f".format(it.total)) }
        b.seriesId?.let { parts.add("Series: $it") }
        return parts.joinToString("\n")
    }

    /** Escape per RFC 5545 §3.3.11 (TEXT). */
    private fun escapeText(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")

    /** Escape for property parameters (quote if it contains : ; ,). */
    private fun escapeParam(value: String): String {
        val needsQuote = value.any { it == ':' || it == ';' || it == ',' }
        val cleaned = value.replace("\"", "")
        return if (needsQuote) "\"$cleaned\"" else cleaned
    }

    /**
     * Append a content line with CRLF terminator and 75-octet folding
     * (RFC 5545 §3.1). Continuation lines start with a single space.
     */
    private fun appendLine(sb: StringBuilder, line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) {
            sb.append(line).append("\r\n")
            return
        }
        // Walk by characters but watch byte boundaries so multi-byte UTF-8
        // sequences don't get split mid-codepoint.
        var first = true
        var current = StringBuilder()
        var currentBytes = 0
        for (ch in line) {
            val chBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            val budget = if (first) 75 else 74
            if (currentBytes + chBytes > budget) {
                if (first) {
                    sb.append(current).append("\r\n")
                    first = false
                } else {
                    sb.append(' ').append(current).append("\r\n")
                }
                current = StringBuilder()
                currentBytes = 0
            }
            current.append(ch)
            currentBytes += chBytes
        }
        if (current.isNotEmpty()) {
            if (first) sb.append(current).append("\r\n")
            else sb.append(' ').append(current).append("\r\n")
        }
    }
}
