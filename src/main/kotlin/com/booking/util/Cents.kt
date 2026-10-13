package com.booking.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Helpers for working with money as whole minor units (cents).
 *
 * The membership subsystem keeps every amount as a [Long] number of cents so
 * proration and rollover arithmetic never accumulates floating-point drift.
 */
object Cents {

    /** Format [cents] as `$12.34`, or `-$12.34` for negatives. */
    fun format(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = Math.abs(cents)
        return "%s$%d.%02d".format(sign, abs / 100, abs % 100)
    }

    /** Convert a dollar amount (e.g. from a CSV cell) to cents, rounding half-up. */
    fun fromDollars(dollars: Double): Long {
        require(!dollars.isNaN() && !dollars.isInfinite()) { "Amount must be a finite number." }
        return BigDecimal.valueOf(dollars).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
    }

    /** Parse `"12"`, `"12.5"`, `"12.50"` or `"$12.50"` into cents. */
    fun parse(text: String): Long {
        val cleaned = text.trim().removePrefix("$").replace(",", "")
        require(cleaned.isNotEmpty()) { "Amount cannot be empty." }
        val value = cleaned.toBigDecimalOrNull() ?: throw IllegalArgumentException("Not a valid amount: '$text'")
        return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
    }

    /** Percentage of [cents], rounded half-up. [percent] may be fractional. */
    fun percentOf(cents: Long, percent: Double): Long {
        require(percent >= 0.0) { "Percent cannot be negative." }
        return BigDecimal.valueOf(cents)
            .multiply(BigDecimal.valueOf(percent))
            .divide(BigDecimal(100), 0, RoundingMode.HALF_UP)
            .toLong()
    }

    /** Scale [cents] by the ratio [numerator]/[denominator], rounding half-up. */
    fun ratio(cents: Long, numerator: Long, denominator: Long): Long {
        require(denominator > 0) { "Denominator must be positive." }
        return BigDecimal.valueOf(cents)
            .multiply(BigDecimal.valueOf(numerator))
            .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP)
            .toLong()
    }
}
