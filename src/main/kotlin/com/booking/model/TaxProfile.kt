package com.booking.model

/**
 * Tax category attached to an [InvoiceLine].
 *
 * Categories are deliberately coarse — this is a booking system, not an
 * accounting package — but they are enough to model the common split
 * between full-rate services, reduced-rate items (e.g. catering added to
 * a room booking in some jurisdictions), and zero-rated/exempt lines
 * (deposits, pass-through fees).
 */
enum class TaxCategory {
    STANDARD,
    REDUCED,
    ZERO;

    companion object {
        /** Lenient parse used by the CLI; returns null for unknown input. */
        fun parse(raw: String): TaxCategory? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * How the [com.booking.service.TaxCalculator] rounds computed tax.
 *
 *  * [PER_LINE] — each line's tax is rounded to cents independently, then
 *    summed. This is what most POS systems do and what customers can
 *    verify line-by-line on the printed invoice.
 *  * [PER_INVOICE] — tax is accumulated at full precision per category and
 *    rounded once at the end. Produces totals that can differ from the
 *    per-line sum by a cent or two on long invoices; some tax authorities
 *    require exactly this.
 */
enum class TaxRoundingStrategy { PER_LINE, PER_INVOICE }

/**
 * A named set of tax rates, one percentage per [TaxCategory].
 *
 * A profile is immutable; to change a rate, register a new profile. This
 * keeps already-issued invoices stable — an invoice snapshots its computed
 * tax lines at issue time, so later profile changes never rewrite history.
 */
data class TaxProfile(
    val name: String,
    /** Percentage (e.g. 8.875 for NYC), keyed by category. Missing keys are treated as 0%. */
    val rates: Map<TaxCategory, Double>,
    val rounding: TaxRoundingStrategy = TaxRoundingStrategy.PER_LINE,
    /** Free-text registration/jurisdiction line printed on invoices (e.g. a VAT number). */
    val registrationNote: String? = null
) {
    init {
        require(name.isNotBlank()) { "Tax profile name cannot be blank." }
        rates.forEach { (category, percent) ->
            require(percent >= 0.0) { "Rate for $category cannot be negative." }
            require(percent <= 100.0) { "Rate for $category cannot exceed 100%." }
        }
        require(rates[TaxCategory.ZERO].let { it == null || it == 0.0 }) {
            "The ZERO category must have a 0% rate."
        }
    }

    fun rateFor(category: TaxCategory): Double = rates[category] ?: 0.0

    companion object {
        /** Sensible default so the CLI works out of the box: 10% standard, 5% reduced. */
        val DEFAULT = TaxProfile(
            name = "default",
            rates = mapOf(
                TaxCategory.STANDARD to 10.0,
                TaxCategory.REDUCED to 5.0,
                TaxCategory.ZERO to 0.0
            )
        )

        /** A no-tax profile for jurisdictions where the venue handles tax elsewhere. */
        val TAX_FREE = TaxProfile(
            name = "tax-free",
            rates = mapOf(
                TaxCategory.STANDARD to 0.0,
                TaxCategory.REDUCED to 0.0,
                TaxCategory.ZERO to 0.0
            )
        )
    }
}
