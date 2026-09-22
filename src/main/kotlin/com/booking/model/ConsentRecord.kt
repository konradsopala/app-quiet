package com.booking.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * One consent decision by one data subject for one processing purpose.
 *
 * Records are append-only evidence: withdrawing consent sets
 * [withdrawnAt] rather than deleting the row, because "we had consent
 * from 2026-03-01 to 2026-08-14" is exactly what a regulator asks for.
 * A fresh grant after a withdrawal is a new record.
 *
 * [subjectId] is the customer id while the customer exists and their
 * pseudonym token after erasure, so the consent history survives the
 * subject's personal data being removed.
 */
class ConsentRecord(
    subjectId: String,
    val purpose: Purpose,
    granted: Boolean,
    source: String,
    val recordedAt: LocalDateTime = LocalDateTime.now(),
    /** Consent that lapses on its own; `null` means it stands until withdrawn. */
    val expiresAt: LocalDate? = null,
    id: String? = null
) {
    /**
     * What the data is used for. [lawfulBasis] documents why the
     * processing is allowed when consent is *not* the basis, so the
     * consent screen can explain that service messages cannot be opted
     * out of while marketing can.
     */
    enum class Purpose(val description: String, val lawfulBasis: String, val requiresConsent: Boolean) {
        SERVICE_MESSAGES("Booking confirmations, reminders and receipts", "contract", false),
        MARKETING("Promotional email and SMS", "consent", true),
        REVIEW_PUBLICATION("Showing the customer's name next to their review", "consent", true),
        ANALYTICS("Including the customer in utilisation and revenue analytics", "legitimate interest", false),
        THIRD_PARTY_SHARING("Sharing contact details with partner venues", "consent", true)
    }

    val id: String = id ?: ("cns_" + UUID.randomUUID().toString().replace("-", "").take(16))

    var subjectId: String = subjectId.trim()
        internal set

    /** Where the decision was captured: "cli", "web form", "paper form", "phone". */
    val source: String = source.trim()

    var granted: Boolean = granted
        private set

    var withdrawnAt: LocalDateTime? = null
        private set

    init {
        require(this.subjectId.isNotEmpty()) { "subjectId cannot be blank" }
        require(this.source.isNotEmpty()) { "source cannot be blank" }
    }

    /** True when this record grants the purpose on [date] and has not been withdrawn or lapsed. */
    fun isEffectiveOn(date: LocalDate): Boolean =
        granted && withdrawnAt == null && (expiresAt == null || !date.isAfter(expiresAt))

    internal fun withdraw(at: LocalDateTime) {
        check(withdrawnAt == null) { "Consent $id was already withdrawn at $withdrawnAt" }
        withdrawnAt = at
    }

    /** Snapshot restore: bring the mutable fields back without re-validating transitions. */
    internal fun restoreState(granted: Boolean, withdrawnAt: LocalDateTime?) {
        this.granted = granted
        this.withdrawnAt = withdrawnAt
    }

    override fun toString(): String {
        val state = when {
            withdrawnAt != null -> "withdrawn $withdrawnAt"
            !granted -> "declined"
            expiresAt != null && LocalDate.now().isAfter(expiresAt) -> "expired $expiresAt"
            else -> "granted"
        }
        return "[$id] $subjectId ${purpose.name} — $state (via $source, ${recordedAt.toLocalDate()})"
    }
}
