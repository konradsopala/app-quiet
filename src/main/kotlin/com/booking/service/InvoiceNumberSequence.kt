package com.booking.service

import java.time.LocalDate

/**
 * Generates sequential, human-readable invoice numbers.
 *
 * Format: `<prefix>-<year>-<counter>`, e.g. `INV-2026-00042`. The counter
 * is zero-padded to [padWidth] digits and resets to 1 at the first issue of
 * a new year — a common legal requirement for gap-free yearly numbering.
 *
 * Credit notes share the same counter but use their own prefix, so a
 * credit note issued between two invoices doesn't create a gap in either
 * sequence... it creates one shared chronological sequence instead, which
 * is what most small-business accounting expects.
 *
 * Not thread-safe: the CLI is single-threaded, matching the rest of the
 * services in this codebase.
 */
class InvoiceNumberSequence(
    private val prefix: String = "INV",
    private val creditNotePrefix: String = "CN",
    private val padWidth: Int = 5
) {
    init {
        require(prefix.isNotBlank()) { "Prefix cannot be blank." }
        require(creditNotePrefix.isNotBlank()) { "Credit note prefix cannot be blank." }
        require(padWidth in 3..10) { "padWidth must be between 3 and 10." }
    }

    private var currentYear: Int = 0
    private var counter: Int = 0

    /** The number that [next] would return for [onDate], without consuming it. */
    fun peek(onDate: LocalDate, creditNote: Boolean = false): String {
        val nextCounter = if (onDate.year != currentYear) 1 else counter + 1
        return format(onDate.year, nextCounter, creditNote)
    }

    /** Consume and return the next number in sequence for [onDate]. */
    fun next(onDate: LocalDate, creditNote: Boolean = false): String {
        if (onDate.year != currentYear) {
            currentYear = onDate.year
            counter = 0
        }
        counter += 1
        return format(currentYear, counter, creditNote)
    }

    /** How many numbers have been issued in the current year. */
    fun issuedThisYear(): Int = counter

    /**
     * Restore the sequence position from a snapshot. [lastCounter] is the
     * highest counter already consumed for [year]; the next call to [next]
     * in the same year returns `lastCounter + 1`.
     */
    internal fun restore(year: Int, lastCounter: Int) {
        require(lastCounter >= 0) { "lastCounter cannot be negative." }
        currentYear = year
        counter = lastCounter
    }

    private fun format(year: Int, value: Int, creditNote: Boolean): String {
        val p = if (creditNote) creditNotePrefix else prefix
        return "$p-$year-" + value.toString().padStart(padWidth, '0')
    }
}
