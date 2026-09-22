package com.booking.privacy

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Turns personal data into something that is still useful for
 * reporting but no longer identifies a person.
 *
 *   * [pseudonym] is deterministic per subject and keyed by a secret, so
 *     an erased customer's bookings still group together in analytics
 *     while nobody without the key can reverse the token.
 *   * [maskEmail] / [maskPhone] keep just enough shape for an operator to
 *     recognise "yes, that's the one" on a support call.
 *   * [scrub] sweeps free text — booking notes, audit details, review
 *     comments — for emails, phone numbers, card numbers and the
 *     subject's own names.
 *
 * Everything here is pure string processing. Nothing is logged.
 */
class PiiRedactor(secret: String) {

    companion object {
        const val MIN_SECRET_LENGTH = 16
        const val TOKEN_PREFIX = "Subject-"
        const val REDACTED_EMAIL = "[redacted email]"
        const val REDACTED_PHONE = "[redacted phone]"
        const val REDACTED_CARD = "[redacted card]"
        const val REDACTED_NAME = "[redacted name]"

        private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        /** 7+ digits with optional separators, optionally prefixed with + and a country code. */
        private val PHONE = Regex("""(?<![\w.])(\+?\d[\d\s().-]{6,}\d)(?![\w.])""")
        /** 13–19 digit runs with optional spaces/dashes; confirmed by Luhn before redaction. */
        private val CARD = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")

        fun luhnValid(digits: String): Boolean {
            if (digits.length < 13) return false
            var sum = 0
            var alternate = false
            for (i in digits.indices.reversed()) {
                var d = digits[i] - '0'
                if (alternate) { d *= 2; if (d > 9) d -= 9 }
                sum += d
                alternate = !alternate
            }
            return sum % 10 == 0
        }
    }

    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    init {
        require(secret.length >= MIN_SECRET_LENGTH) {
            "Pseudonym secret must be at least $MIN_SECRET_LENGTH characters"
        }
    }

    /** Stable, opaque replacement for a subject's name. Same input + same key → same token. */
    fun pseudonym(subjectKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val digest = mac.doFinal(subjectKey.trim().lowercase().toByteArray(Charsets.UTF_8))
        val hex = digest.take(5).joinToString("") { "%02X".format(it) }
        return TOKEN_PREFIX + hex
    }

    fun isPseudonym(value: String): Boolean = value.startsWith(TOKEN_PREFIX)

    /** `jane.doe@example.com` → `j***@example.com`. Invalid input is fully masked. */
    fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0 || at == email.length - 1) return "***"
        return email[0] + "***" + email.substring(at)
    }

    /** Keeps the last four digits only: `+1 (555) 010-2345` → `***2345`. */
    fun maskPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 4) return "***"
        return "***" + digits.takeLast(4)
    }

    /**
     * Replace anything in [text] that looks like contact data or a card
     * number, plus every whole-word occurrence of the given [names]
     * (case-insensitive). Returns null for null input so callers can
     * assign straight back to an optional field.
     */
    fun scrub(text: String?, names: Collection<String> = emptyList()): String? {
        if (text == null) return null
        var out: String = text
        out = CARD.replace(out) { m ->
            val digits = m.value.filter { it.isDigit() }
            if (luhnValid(digits)) REDACTED_CARD else m.value
        }
        out = EMAIL.replace(out, REDACTED_EMAIL)
        out = PHONE.replace(out) { m ->
            // Leave short numeric ids (booking refs, amounts) alone; E.164 caps a phone at 15 digits,
            // so longer runs are account or card-like numbers handled (or deliberately left) above.
            if (m.value.count { it.isDigit() } in 7..15) REDACTED_PHONE else m.value
        }
        for (name in names.map { it.trim() }.filter { it.length >= 2 }.sortedByDescending { it.length }) {
            out = Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(name) + "(?![\\p{L}\\p{N}])").replace(out, REDACTED_NAME)
            // Also catch the individual name parts ("Jane", "Doe") when the full name had several.
            for (part in name.split(Regex("\\s+")).filter { it.length >= 3 }) {
                out = Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(part) + "(?![\\p{L}\\p{N}])").replace(out, REDACTED_NAME)
            }
        }
        return out
    }

    /** Convenience for non-null fields. */
    fun scrubOrEmpty(text: String, names: Collection<String> = emptyList()): String = scrub(text, names) ?: ""
}
