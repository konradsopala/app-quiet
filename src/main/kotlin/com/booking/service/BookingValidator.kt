package com.booking.service

import com.booking.model.Booking
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Composable validation rules engine for booking operations.
 *
 * Centralizes all business rules (duplicate detection, advance notice, max per customer,
 * weekend blocking, time-slot capacity) and reports all violations at once via
 * [ValidationResult].
 */
class BookingValidator(private val service: BookingService) {

    data class ValidationResult(val valid: Boolean, val errors: List<String>) {
        companion object {
            fun ok(): ValidationResult = ValidationResult(true, emptyList())
            fun fail(errors: List<String>): ValidationResult = ValidationResult(false, errors.toList())
        }
    }

    var maxBookingsPerCustomer: Int = 10
    var minAdvanceDays: Int = 1
    var blockWeekends: Boolean = false

    // ── Validate new booking ─────────────────────────────────────

    fun validateNewBooking(
        customerName: String?,
        date: LocalDate?,
        startTime: LocalTime?,
        durationMinutes: Int?,
        description: String?,
        tags: Set<String> = emptySet(),
        internalReference: String? = null
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

        if (startTime != null && durationMinutes != null && durationMinutes > 0) {
            val endMinutes = startTime.toSecondOfDay() / 60 + durationMinutes
            if (endMinutes > 24 * 60) {
                errors.add("Booking must end on the same day (start $startTime + ${durationMinutes}m crosses midnight).")
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

        if (date != null && startTime != null && durationMinutes != null && durationMinutes > 0) {
            val capacityError = checkCapacity(date, startTime, durationMinutes, excludeId = null)
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

        // If any time-related field is being touched, re-check capacity using
        // the resulting (possibly mixed old+new) values.
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
                        val capacityError = checkCapacity(
                            effectiveDate, effectiveStart, effectiveDuration, excludeId = bookingId
                        )
                        if (capacityError != null) errors.add(capacityError)
                    }
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.ok() else ValidationResult.fail(errors)
    }

    // Returns an error string if accepting the proposed slot would push the
    // count of overlapping confirmed bookings past [BookingService.capacity].
    private fun checkCapacity(
        date: LocalDate,
        start: LocalTime,
        durationMinutes: Int,
        excludeId: String?
    ): String? {
        val overlapping = service.overlappingBookings(date, start, durationMinutes, excludeId)
        return if (overlapping.size >= service.capacity) {
            "Time slot is full: ${overlapping.size} confirmed booking(s) overlap " +
                "(capacity ${service.capacity})."
        } else null
    }
}
