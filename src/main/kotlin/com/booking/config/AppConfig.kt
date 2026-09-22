package com.booking.config

import java.time.LocalTime

/**
 * Centralised defaults for the booking system.
 *
 * Values that used to live as magic numbers across services (default
 * capacity, ISO currency, file paths) now resolve through this object so
 * callers can read consistent defaults and so an integration test can
 * swap them via [withDefaults].
 *
 * This is intentionally a `data class` rather than a singleton: it makes
 * it trivial to construct a test-only config without polluting global
 * state, and gives Kotlin's `copy` semantics for free.
 */
data class AppConfig(
    val defaultCapacity: Int = 1,
    val defaultCurrency: String = "USD",
    val defaultCsvPath: String = "bookings.csv",
    val defaultIcsPath: String = "bookings.ics",
    val defaultRefundRegisterPath: String = "refund-register.csv",
    val defaultCustomersCsvPath: String = "customers.csv",
    val recentAdvanceDays: Long = 2,
    val priceFloor: Double = 25.0,
    /** Hardest cap on booking duration (BookingValidator rejects anything longer). */
    val maxBookingDurationMinutes: Int = 8 * 60,
    /** Inclusive opening time — bookings must start at or after this. */
    val businessHoursOpen: LocalTime = LocalTime.of(8, 0),
    /** Exclusive closing time — bookings must end at or before this. */
    val businessHoursClose: LocalTime = LocalTime.of(22, 0),
    /** Payment terms: an issued invoice falls due this many days after its issue date. */
    val invoiceNetDays: Long = 14,
    val defaultInvoicesCsvPath: String = "invoices.csv",
    /** Statutory window for answering a data-subject request (GDPR art. 12: one month). */
    val privacyRequestSlaDays: Long = 30,
    /** Cancelled bookings older than this lose the customer's name and free text. */
    val retentionCancelledBookingDays: Long = 365,
    /** Processor failure strings are cleared after this; they can quote card fragments. */
    val retentionPaymentFailureReasonDays: Long = 540,
    /** Waitlist entries whose slot date is this far in the past are dropped. */
    val retentionStaleWaitlistDays: Long = 90,
    /** Audit details older than this are scrubbed of contact data; entries are never deleted. */
    val retentionAuditDetailDays: Long = 730,
    /** Customers with no booking for this long are listed as erasure candidates. */
    val retentionInactiveCustomerDays: Long = 1095,
    /**
     * HMAC key behind pseudonyms. Anyone holding it can link an erased
     * subject's pseudonym back to the original id, so it must be replaced
     * per deployment and kept out of snapshots and source control.
     */
    val pseudonymSecret: String = "change-me-pseudonym-secret-0000",
    /** Where subject-access exports are written. */
    val defaultSubjectExportDir: String = "exports"
) {
    init {
        require(defaultCapacity >= 1) { "defaultCapacity must be >= 1" }
        require(defaultCurrency.length == 3) { "defaultCurrency must be a 3-letter ISO code" }
        require(priceFloor >= 0) { "priceFloor cannot be negative" }
        require(recentAdvanceDays >= 0) { "recentAdvanceDays cannot be negative" }
        require(maxBookingDurationMinutes in 1..(24 * 60)) {
            "maxBookingDurationMinutes must be in 1..1440"
        }
        require(businessHoursOpen < businessHoursClose) {
            "businessHoursOpen must be strictly before businessHoursClose"
        }
        require(invoiceNetDays >= 0) { "invoiceNetDays cannot be negative" }
        require(privacyRequestSlaDays in 1..90) { "privacyRequestSlaDays must be in 1..90" }
        require(pseudonymSecret.length >= 16) { "pseudonymSecret must be at least 16 characters" }
        require(defaultSubjectExportDir.isNotBlank()) { "defaultSubjectExportDir cannot be blank" }
    }

    companion object {
        /** The single config used when the app boots normally. */
        val DEFAULT = AppConfig()

        /** Convenience for tests that only want to override a subset. */
        fun withDefaults(
            defaultCapacity: Int = DEFAULT.defaultCapacity,
            defaultCurrency: String = DEFAULT.defaultCurrency,
            defaultCsvPath: String = DEFAULT.defaultCsvPath,
            defaultIcsPath: String = DEFAULT.defaultIcsPath,
            maxBookingDurationMinutes: Int = DEFAULT.maxBookingDurationMinutes,
            businessHoursOpen: LocalTime = DEFAULT.businessHoursOpen,
            businessHoursClose: LocalTime = DEFAULT.businessHoursClose
        ): AppConfig = AppConfig(
            defaultCapacity = defaultCapacity,
            defaultCurrency = defaultCurrency,
            defaultCsvPath = defaultCsvPath,
            defaultIcsPath = defaultIcsPath,
            maxBookingDurationMinutes = maxBookingDurationMinutes,
            businessHoursOpen = businessHoursOpen,
            businessHoursClose = businessHoursClose
        )
    }
}
