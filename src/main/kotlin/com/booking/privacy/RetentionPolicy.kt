package com.booking.privacy

import com.booking.config.AppConfig
import java.time.LocalDate

/**
 * How long each category of personal data is kept before it is
 * minimised. All windows are in days and are applied by
 * [com.booking.service.PrivacyService.applyRetention]; this class only
 * answers "is this record past its window?".
 *
 * The defaults are conservative and configurable through [AppConfig]:
 *
 *   * cancelled bookings keep the customer's name for a year (disputes),
 *     then it is pseudonymised and free text scrubbed;
 *   * payment failure reasons — processor error strings that can quote
 *     card fragments — are cleared after 18 months;
 *   * waitlist entries whose date has passed are dropped after 90 days;
 *   * audit details are scrubbed of contact data after two years, but
 *     the entries themselves are never deleted;
 *   * customers with no booking for three years are *reported* as
 *     erasure candidates, never erased automatically.
 */
data class RetentionPolicy(
    val cancelledBookingDays: Long,
    val paymentFailureReasonDays: Long,
    val staleWaitlistDays: Long,
    val auditDetailDays: Long,
    val inactiveCustomerDays: Long
) {
    init {
        require(cancelledBookingDays >= 30) { "cancelledBookingDays must be at least 30" }
        require(paymentFailureReasonDays >= 30) { "paymentFailureReasonDays must be at least 30" }
        require(staleWaitlistDays >= 1) { "staleWaitlistDays must be at least 1" }
        require(auditDetailDays >= cancelledBookingDays) {
            "auditDetailDays must not be shorter than cancelledBookingDays, or audit would outlive its subject data"
        }
        require(inactiveCustomerDays >= 365) { "inactiveCustomerDays must be at least a year" }
    }

    fun cancelledBookingCutoff(asOf: LocalDate): LocalDate = asOf.minusDays(cancelledBookingDays)
    fun paymentFailureCutoff(asOf: LocalDate): LocalDate = asOf.minusDays(paymentFailureReasonDays)
    fun staleWaitlistCutoff(asOf: LocalDate): LocalDate = asOf.minusDays(staleWaitlistDays)
    fun auditDetailCutoff(asOf: LocalDate): LocalDate = asOf.minusDays(auditDetailDays)
    fun inactiveCustomerCutoff(asOf: LocalDate): LocalDate = asOf.minusDays(inactiveCustomerDays)

    /** What one sweep did. Counts only; never the records themselves. */
    data class Report(
        val asOf: LocalDate,
        val bookingsMinimised: Int,
        val paymentReasonsCleared: Int,
        val waitlistEntriesDropped: Int,
        val auditEntriesScrubbed: Int,
        val inactiveCustomerCandidates: List<String>,
        val dryRun: Boolean
    ) {
        val touched: Int get() = bookingsMinimised + paymentReasonsCleared + waitlistEntriesDropped + auditEntriesScrubbed

        fun render(): String = buildString {
            appendLine("Retention sweep as of $asOf" + if (dryRun) " (dry run — nothing changed)" else "")
            appendLine("  Cancelled bookings minimised:   $bookingsMinimised")
            appendLine("  Payment failure reasons cleared: $paymentReasonsCleared")
            appendLine("  Stale waitlist entries dropped:  $waitlistEntriesDropped")
            appendLine("  Audit details scrubbed:          $auditEntriesScrubbed")
            appendLine("  Inactive customers (review for erasure): ${inactiveCustomerCandidates.size}")
            inactiveCustomerCandidates.take(20).forEach { appendLine("    - $it") }
            if (inactiveCustomerCandidates.size > 20) appendLine("    … and ${inactiveCustomerCandidates.size - 20} more")
        }
    }

    companion object {
        fun fromConfig(config: AppConfig): RetentionPolicy = RetentionPolicy(
            cancelledBookingDays = config.retentionCancelledBookingDays,
            paymentFailureReasonDays = config.retentionPaymentFailureReasonDays,
            staleWaitlistDays = config.retentionStaleWaitlistDays,
            auditDetailDays = config.retentionAuditDetailDays,
            inactiveCustomerDays = config.retentionInactiveCustomerDays
        )
    }
}
