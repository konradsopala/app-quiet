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
    /** Gift cards expire this many months after issue unless sold as perpetual. */
    val giftCardExpiryMonths: Long = 12,
    /** Smallest value a gift card can be sold or reloaded for. */
    val minGiftCardValue: Double = 5.0,
    /** Per-card exposure cap: issue + reloads may never push a balance above this. */
    val maxGiftCardValue: Double = 1_000.0,
    val defaultGiftCardsCsvPath: String = "gift-cards.csv",
    val defaultGiftCardLedgerCsvPath: String = "gift-card-ledger.csv",
    /** Consecutive failed sign-ins before an operator account locks. */
    val maxFailedSignIns: Int = 5,
    /** How long a locked operator account stays locked. */
    val signInLockoutMinutes: Long = 15,
    /** Voiding a card whose balance is above this needs a second, manager-level approval. */
    val giftCardVoidApprovalThreshold: Double = 100.0,
    /**
     * First-run administrator. Created only when no operators exist, and
     * flagged so the PIN has to be changed before any privileged action.
     */
    val bootstrapAdminUsername: String = "admin",
    val bootstrapAdminPin: String = "246810",
    /** Outbound webhook for booking / payment / gift-card events; null leaves the channel unregistered. */
    val webhookUrl: String? = null,
    /** Shared secret for the webhook HMAC; required when [webhookUrl] is set. */
    val webhookSecret: String? = null,
    val webhookTimeoutMillis: Int = 5_000
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
        require(giftCardExpiryMonths >= 1) { "giftCardExpiryMonths must be at least 1" }
        require(minGiftCardValue > 0) { "minGiftCardValue must be positive" }
        require(maxGiftCardValue >= minGiftCardValue) {
            "maxGiftCardValue must be at least minGiftCardValue"
        }
        require(maxFailedSignIns in 1..20) { "maxFailedSignIns must be in 1..20" }
        require(signInLockoutMinutes >= 1) { "signInLockoutMinutes must be at least 1" }
        require(giftCardVoidApprovalThreshold >= 0) { "giftCardVoidApprovalThreshold cannot be negative" }
        require(bootstrapAdminUsername.isNotBlank()) { "bootstrapAdminUsername cannot be blank" }
        require(webhookUrl == null || !webhookSecret.isNullOrBlank()) {
            "webhookSecret is required when webhookUrl is set"
        }
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
