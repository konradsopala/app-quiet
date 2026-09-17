package com.booking.util

import java.security.SecureRandom
import java.util.Random

/**
 * Generates and validates customer-facing gift card codes.
 *
 * Codes are 12 characters from a 32-symbol alphabet that drops the letters
 * most often confused with digits (`I`, `L`, `O`, `Z`), rendered as
 * `GC-XXXX-XXXX-XXXX`. The final character is a check character computed
 * with the Luhn mod-N algorithm, so a single mistyped or transposed
 * character is caught before the code ever reaches the ledger. The
 * alphabet size is deliberately even: Luhn mod N only guarantees every
 * single-symbol substitution is detected when N is even.
 *
 * [normalize] is forgiving about what the operator types: case, spaces,
 * dashes and the optional `GC` prefix are all ignored, and the dropped
 * look-alike letters (`O`, `I`, `L`, `Z`) are mapped back to the digit they resemble. The
 * result is either a canonical code or `null` if the input can't be one.
 */
object GiftCardCodeGenerator {

    /** 0-9 plus A-Z without I, L, O, Z (32 symbols). Order matters — it defines each symbol's value. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTUVWXY"

    /** Body length including the trailing check character. */
    const val BODY_LENGTH = 12

    const val PREFIX = "GC"

    private const val GROUP = 4

    private val secureRandom: Random = SecureRandom()

    // ── Generation ────────────────────────────────────────────────────

    /**
     * Produce a fresh canonical code. Uniqueness is the caller's concern —
     * the service retries on collision — but with 33^11 possible bodies a
     * clash is astronomically unlikely (32^11 ≈ 3.6 × 10^16 bodies).
     */
    fun generate(random: Random = secureRandom): String {
        val body = StringBuilder(BODY_LENGTH)
        repeat(BODY_LENGTH - 1) {
            body.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        body.append(checkCharacter(body.toString()))
        return format(body.toString())
    }

    /** Render a bare 12-character body as `GC-XXXX-XXXX-XXXX`. */
    fun format(body: String): String {
        require(body.length == BODY_LENGTH) { "Body must be $BODY_LENGTH characters, got ${body.length}." }
        return PREFIX + "-" + body.chunked(GROUP).joinToString("-")
    }

    // ── Normalisation & validation ────────────────────────────────────

    /**
     * Turn whatever the operator typed into a canonical code, or `null`
     * when it can't be one (wrong length, symbols outside the alphabet, or
     * a failed check character).
     */
    fun normalize(input: String): String? {
        var cleaned = input.uppercase()
            .filter { !it.isWhitespace() && it != '-' && it != '_' }
        if (cleaned.startsWith(PREFIX)) cleaned = cleaned.substring(PREFIX.length)
        cleaned = cleaned.map { substituteLookAlike(it) }.joinToString("")
        if (cleaned.length != BODY_LENGTH) return null
        if (cleaned.any { it !in ALPHABET }) return null
        if (!hasValidCheckCharacter(cleaned)) return null
        return format(cleaned)
    }

    /** True when [input] normalises to a well-formed code. */
    fun isValid(input: String): Boolean = normalize(input) != null

    private fun substituteLookAlike(c: Char): Char = when (c) {
        'O' -> '0'
        'I', 'L' -> '1'
        'Z' -> '2'
        else -> c
    }

    // ── Luhn mod N ────────────────────────────────────────────────────

    /**
     * Compute the check character for [payload] (the first
     * [BODY_LENGTH] - 1 symbols) using the Luhn mod-N algorithm over
     * [ALPHABET].
     */
    fun checkCharacter(payload: String): Char {
        val n = ALPHABET.length
        var factor = 2
        var sum = 0
        // Walk right-to-left, doubling every other symbol starting from the rightmost.
        for (i in payload.indices.reversed()) {
            val codePoint = ALPHABET.indexOf(payload[i])
            require(codePoint >= 0) { "Symbol '${payload[i]}' is not in the alphabet." }
            var addend = factor * codePoint
            factor = if (factor == 2) 1 else 2
            addend = (addend / n) + (addend % n)
            sum += addend
        }
        val remainder = sum % n
        val checkCodePoint = (n - remainder) % n
        return ALPHABET[checkCodePoint]
    }

    private fun hasValidCheckCharacter(body: String): Boolean {
        val n = ALPHABET.length
        var factor = 1
        var sum = 0
        for (i in body.indices.reversed()) {
            val codePoint = ALPHABET.indexOf(body[i])
            if (codePoint < 0) return false
            var addend = factor * codePoint
            factor = if (factor == 2) 1 else 2
            addend = (addend / n) + (addend % n)
            sum += addend
        }
        return sum % n == 0
    }
}
