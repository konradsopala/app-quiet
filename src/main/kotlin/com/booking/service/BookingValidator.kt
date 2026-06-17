package com.booking.service

import com.booking.config.AppConfig
import com.booking.model.Booking
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Composable validation rules engine for booking operations.
 *
 * Centralizes all business rules (duplicate detection, advance notice, max per customer,
 * weekend blocking, max duration, business-hours window, time-slot capacity) and
 * reports all violations at once via [ValidationResult].
 */
class BookingValidator(
    private val service: BookingService,
    private val config: AppConfig = AppConfig.DEFAULT
) {

    data class ValidationResult(val valid: Boolean, val errors: List<String>) {
        companion object {
            fun ok(): ValidationResult = ValidationResult(true, emptyList())
            fun fail(errors: List<String>): ValidationResult = ValidationResult(false, errors.toList())
        }
    }

    var maxBookingsPerCustomer: Int = 10
    var minAdvanceDays: Int = 1
    var blockWeekends: Boolean = false

    /** Mutable mirrors of the config defaults — exposed so operators can tweak at runtime. */
    var maxDurationMinutes: Int = config.maxBookingDurationMinutes
        set(value) {
            require(value in 1..(24 * 60)) { "maxDurationMinutes must be in 1..1440" }
            field = value
        }
    var businessHoursOpen: LocalTime = config.businessHoursOpen
    var businessHoursClose: LocalTime = config.businessHoursClose
        set(value) {
            require(businessHoursOpen < value) { "close time must be after open time" }
            field = value
        }
    var enforceBusinessHours: Boolean = true

    // ── Validate new booking ─────────────────────────────────────

    fun validateNewBooking(
        customerName: String?,
        date: LocalDate?,
        startTime: LocalTime?,
        durationMinutes: Int?,
        description: String?,
        tags: Set<String> = emptySet(),
        internalReference: String? = null,
        resourceId: String? = null
    ): ValidationResult {
        val errors = mutableListOf<String>()

        if (customerName.isNullOrBlank()) {
            errors.add("Customer name cannot be empty.")
        }

        // Tag invariants — must be non-blank, comma-free (commas are CSV /
        // iCal CATEGORIES separators), and at most 30 chars. Booking.addTag
        // re-checks the comma rule but enforcing here lets us report all
        // tag problems alongside the other field errors.
        for (raw in tags) {
            val t = raw.trim()
            when {
                t.isEmpty() -> errors.add("Tag cannot be blank.")
                ',' in t    -> errors.add("Tag '$raw' cannot contain a comma.")
                t.length > 30 -> errors.add("Tag '$raw' is longer than 30 characters.")
            }
        }
        if (internalReference != null && internalReference.length > 64) {
            errors.add("internalReference is longer than 64 characters.")
        }

        if (date != null && date.isBefore(LocalDate.now())) {
            errors.add("Booking date cannot be in the past.")
        } else if (date != null && date.isBefore(LocalDate.now().plusDays(minAdvanceDays.toLong()))) {
            errors.add("Booking must be at least $minAdvanceDays day(s) in advance.")
        }

        if (blockWeekends && date != null) {
            val dow = date.dayOfWeek
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                errors.add("Weekend bookings are not allowed.")
            }
        }

        if (durationMinutes != null && durationMinutes <= 0) {
            errors.add("Duration must be a positive number of minutes.")
        }
        if (durationMinutes != null && durationMinutes > maxDurationMinutes) {
            errors.add(
                "Duration ${durationMinutes}m exceeds maximum of ${maxDurationMinutes}m " +
                    "(${maxDurationMinutes / 60}h)."
            )
        }

        if (startTime != null && durationMinutes != null && durationMinutes > 0) {
            val endMinutes = startTime.toSecondOfDay() / 60 + durationMinutes
            if (endMinutes > 24 * 60) {
                errors.add("Booking must end on the same day (start $startTime + ${durationMinutes}m crosses midnight).")
            } else if (enforceBusinessHours) {
                businessHoursError(startTime, durationMinutes)?.let { errors.add(it) }
            }
        }

        if (customerName != null || date != null) {
            val bookings = service.listBookings()

            if (customerName != null && date != null) {
                val duplicate = bookings
                    .filter { it.status == Booking.Status.CONFIRMED }
                    .any { it.customerName.equals(customerName, ignoreCase = true) && it.date == date }
                if (duplicate) {
                    errors.add("Customer already has a booking on $date.")
                }
            }

            if (customerName != null) {
                val count = bookings
                    .filter { it.status == Booking.Status.CONFIRMED }
                    .count { it.customerName.equals(customerName, ignoreCase = true) }
                if (count >= maxBookingsPerCustomer) {
                    errors.add("Customer has reached the maximum of $maxBookingsPerCustomer bookings.")
                }
            }
        }

        // Resource id has to refer to a registered resource — silently
        // dropping unknown ids onto the default would let typos slip
        // through and load the wrong bucket.
        if (resourceId != null && service.resources.find(resourceId) == null) {
            errors.add("Unknown resource id: $resourceId.")
        }

        if (date != null && startTime != null && durationMinutes != null && durationMinutes > 0) {
            val capacityError = checkCapacity(
                date, startTime, durationMinutes, excludeId = null, resourceId = resourceId
            )
            if (capacityError != null) errors.add(capacityError)
        }

        if (description.isNullOrBlank()) {
            errors.add("Description cannot be empty.")
        }

        return if (errors.isEmpty()) ValidationResult.ok() else ValidationResult.fail(errors)
    }

    // ── Validate update ──────────────────────────────────────────

    fun validateUpdate(
        bookingId: String,
        newDate: LocalDate?,
        newStartTime: LocalTime?,
        newDurationMinutes: Int?
    ): ValidationResult {
        val errors = mutableListOf<String>()

        if (newDate != null && newDate.isBefore(LocalDate.now())) {
            errors.add("New date cannot be in the past.")
        } else if (newDate != null && newDate.isBefore(LocalDate.now().plusDays(minAdvanceDays.toLong()))) {
            errors.add("Rescheduled date must be at least $minAdvanceDays day(s) in advance.")
        }

        if (blockWeekends && newDate != null) {
            val dow = newDate.dayOfWeek
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                errors.add("Cannot reschedule to a weekend.")
            }
        }

        if (newDurationMinutes != null && newDurationMinutes <= 0) {
            errors.add("Duration must be a positive number of minutes.")
        }
        if (newDurationMinutes != null && newDurationMinutes > maxDurationMinutes) {
            errors.add(
                "Duration ${newDurationMinutes}m exceeds maximum of ${maxDurationMinutes}m " +
                    "(${maxDurationMinutes / 60}h)."
            )
        }

        // If any time-related field is being touched, re-check capacity + window
        // using the resulting (possibly mixed old+new) values.
        if (newDate != null || newStartTime != null || newDurationMinutes != null) {
            val current = service.findBooking(bookingId)
            if (current != null) {
                val effectiveDate = newDate ?: current.date
                val effectiveStart = newStartTime ?: current.startTime
                val effectiveDuration = newDurationMinutes ?: current.durationMinutes
                if (effectiveDuration > 0) {
                    val endMinutes = effectiveStart.toSecondOfDay() / 60 + effectiveDuration
                    if (endMinutes > 24 * 60) {
                        errors.add("Booking must end on the same day.")
                    } else {
                        if (enforceBusinessHours) {
                            businessHoursError(effectiveStart, effectiveDuration)?.let { errors.add(it) }
                        }
                        val capacityError = checkCapacity(
                            effectiveDate, effectiveStart, effectiveDuration,
                            excludeId = bookingId,
                            resourceId = current.resourceId
                        )
                        if (capacityError != null) errors.add(capacityError)
                    }
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.ok() else ValidationResult.fail(errors)
    }

    /**
     * Returns an error message if the [start, start + duration] window leaks
     * outside [businessHoursOpen, businessHoursClose]. The check is computed
     * in raw minute counts so durations that end exactly at the close time
     * are accepted.
     */
    private fun businessHoursError(start: LocalTime, durationMinutes: Int): String? {
        val openMin = businessHoursOpen.toSecondOfDay() / 60
        val closeMin = businessHoursClose.toSecondOfDay() / 60
        val startMin = start.toSecondOfDay() / 60
        val endMin = startMin + durationMinutes
        return when {
            startMin < openMin ->
                "Booking starts at $start, before opening time $businessHoursOpen."
            endMin > closeMin ->
                "Booking ends after closing time $businessHoursClose " +
                    "(start $start + ${durationMinutes}m)."
            else -> null
        }
    }

    /**
     * Returns an error string if accepting the proposed slot would push
     * the count of overlapping confirmed bookings past the resource's
     * capacity. Each resource gets its own bucket; a busy ROOM-A doesn't
     * block a booking on ROOM-B.
     */
    private fun checkCapacity(
        date: LocalDate,
        start: LocalTime,
        durationMinutes: Int,
        excludeId: String?,
        resourceId: String?
    ): String? {
        val effective = resourceId ?: ResourceService.MAIN_RESOURCE_ID
        val resource = service.resources.find(effective)
            ?: return "Resource $effective not found (cannot check capacity)."
        val overlapping = service.overlappingBookings(
            date, start, durationMinutes, excludeId, resourceId = effective
        )
        return if (overlapping.size >= resource.capacity) {
            "Time slot is full on ${resource.name}: ${overlapping.size} confirmed " +
                "booking(s) overlap (capacity ${resource.capacity})."
        } else null
    }
}
