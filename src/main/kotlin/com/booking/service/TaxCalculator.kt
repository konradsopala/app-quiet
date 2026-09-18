package com.booking.service

import com.booking.model.Invoice
import com.booking.model.InvoiceLine
import com.booking.model.TaxCategory
import com.booking.model.TaxLine
import com.booking.model.TaxProfile
import com.booking.model.TaxRoundingStrategy
import kotlin.math.abs
import kotlin.math.round

/**
 * Computes the tax lines for a set of invoice lines under a [TaxProfile].
 *
 * The calculator is stateless and pure — it never touches the invoice; the
 * [InvoiceService] snapshots the result onto the document at issue time.
 *
 * Rounding follows the profile's [TaxRoundingStrategy]:
 *
 *  * PER_LINE: each line's tax is rounded to cents, then summed per
 *    category. The printed per-line tax always adds up to the total.
 *  * PER_INVOICE: net amounts are summed per category at full precision
 *    and the tax is rounded once per category.
 *
 * Credit notes flow through the same path: negative net amounts produce
 * negative tax, which correctly reverses the original tax charge.
 */
class TaxCalculator(private val profile: TaxProfile = TaxProfile.DEFAULT) {

    /**
     * Compute one [TaxLine] per category present in [lines]. Categories with
     * a 0% effective rate still produce a line (with zero tax) so the
     * rendered invoice can show "ZERO-rated: 40.00 @ 0%" explicitly —
     * auditors like that.
     */
    fun computeTax(lines: List<InvoiceLine>): List<TaxLine> {
        if (lines.isEmpty()) return emptyList()
        return lines
            .groupBy { it.taxCategory }
            .toSortedMap(compareBy { it.ordinal })
            .map { (category, categoryLines) ->
                val rate = profile.rateFor(category)
                val taxable = categoryLines.sumOf { it.netAmount }
                val tax = when (profile.rounding) {
                    TaxRoundingStrategy.PER_LINE ->
                        categoryLines.sumOf { roundToCents(it.netAmount * rate / 100.0) }
                    TaxRoundingStrategy.PER_INVOICE ->
                        roundToCents(taxable * rate / 100.0)
                }
                TaxLine(category, rate, roundToCents(taxable), tax)
            }
    }

    /** Total tax across all lines — convenience over [computeTax]. */
    fun totalTax(lines: List<InvoiceLine>): Double =
        computeTax(lines).sumOf { it.taxAmount }

    /**
     * Effective blended tax rate for [lines], as a percentage of the net
     * subtotal. Returns 0.0 for an empty or all-zero invoice.
     */
    fun effectiveRatePercent(lines: List<InvoiceLine>): Double {
        val net = lines.sumOf { it.netAmount }
        if (abs(net) < Invoice.CENT_TOLERANCE) return 0.0
        return totalTax(lines) / net * 100.0
    }

    companion object {
        /** Half-up rounding to two decimals, symmetric for negative amounts. */
        fun roundToCents(amount: Double): Double {
            val sign = if (amount < 0) -1.0 else 1.0
            return sign * round(abs(amount) * 100.0) / 100.0
        }
    }
}
