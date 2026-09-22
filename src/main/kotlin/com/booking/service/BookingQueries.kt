package com.booking.service

import com.booking.model.Booking

/**
 * Small read-only query helpers over a [BookingService].
 *
 * Every analytics and reporting path used to open with its own
 * `listBookings().filter { it.status == CONFIRMED }`; centralising the
 * predicate here means a future status (say, `NO_SHOW`) only has to be
 * classified once.
 */

/** All bookings currently in [Booking.Status.CONFIRMED]. */
internal fun BookingService.confirmedBookings(): List<Booking> =
    listBookings().filter { it.status == Booking.Status.CONFIRMED }

/** All bookings currently in [Booking.Status.CANCELLED]. */
internal fun BookingService.cancelledBookings(): List<Booking> =
    listBookings().filter { it.status == Booking.Status.CANCELLED }
