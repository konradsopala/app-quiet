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
    val recentAdvanceDays: Long = 2,
    val priceFloor: Double = 25.0,
    /** Hardest cap on booking duration (BookingValidator rejects anything longer). */
    val maxBookingDurationMinutes: Int = 8 * 60,
    /** Inclusive opening time — bookings must start at or after this. */
    val businessHoursOpen: LocalTime = LocalTime.of(8, 0),
    /** Exclusive closing time — bookings must end at or before this. */
    val businessHoursClose: LocalTime = LocalTime.of(22, 0)
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
