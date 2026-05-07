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
    private val productId: String = "-//Booking System//Booking Manager v2//EN"
) {

    private val localFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val utcFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

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
        appendLine(sb, "ORGANIZER;CN=${escapeParam(b.customerName)}:mailto:noreply@booking-system.local")
        appendLine(sb, "STATUS:${if (b.status == Booking.Status.CONFIRMED) "CONFIRMED" else "CANCELLED"}")
        b.seriesId?.let { appendLine(sb, "CATEGORIES:SERIES-$it") }
        appendLine(sb, "END:VEVENT")
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
