package com.booking.service

import com.booking.model.Booking
import com.booking.model.Review
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDate

/**
 * Post-service customer feedback: one [Review] per completed [Booking],
 * plus the aggregates (average rating, distribution, low-rated
 * follow-up list) an operator needs to act on it.
 *
 * A booking is "completed" once it's [Booking.Status.CONFIRMED] and its
 * date isn't in the future — reviewing a cancelled or not-yet-happened
 * booking would be feedback about a service that never took place.
 * Each booking can carry at most one review; [addReview] rejects a
 * second attempt rather than silently overwriting the first, since a
 * customer changing their mind should go through an explicit removal
 * path instead.
 */
class ReviewService(private val service: BookingService) {

    private val reviewsByBooking = linkedMapOf<String, Review>()

    class ReviewException(message: String) : RuntimeException(message)

    // ── Mutations ────────────────────────────────────────────────────

    /**
     * Records a review for [booking]. Throws [ReviewException] if the
     * booking hasn't actually happened yet (cancelled, or dated in the
     * future) or already has a review attached.
     */
    fun addReview(booking: Booking, rating: Int, comment: String? = null): Review {
        if (booking.status != Booking.Status.CONFIRMED) {
            throw ReviewException("Cannot review booking ${booking.id}: it is ${booking.status}, not CONFIRMED.")
        }
        if (booking.date.isAfter(LocalDate.now())) {
            throw ReviewException("Cannot review booking ${booking.id}: it is scheduled for ${booking.date}, which hasn't happened yet.")
        }
        if (reviewsByBooking.containsKey(booking.id)) {
            throw ReviewException("Booking ${booking.id} already has a review.")
        }

        val review = Review(
            bookingId = booking.id,
            customerName = booking.customerName,
            rating = rating,
            comment = comment,
            customerId = booking.customerId
        )
        reviewsByBooking[booking.id] = review
        service.auditLog.log(
            booking.id, AuditLog.Action.REVIEW_ADDED,
            "${booking.customerName} rated ${review.rating}/${Review.MAX_RATING}" +
                (review.comment?.let { " — \"$it\"" } ?: "")
        )
        return review
    }

    /**
     * Replace the in-memory store with [newReviews]. Used by snapshot
     * restore, mirroring the `replaceAll` convention on the other
     * services.
     */
    internal fun replaceAll(newReviews: List<Review>) {
        reviewsByBooking.clear()
        newReviews.forEach { reviewsByBooking[it.bookingId] = it }
    }

    // ── Queries ──────────────────────────────────────────────────────

    fun list(): List<Review> = reviewsByBooking.values.toList()

    fun reviewForBooking(bookingId: String): Review? = reviewsByBooking[bookingId]

    fun hasReview(bookingId: String): Boolean = reviewsByBooking.containsKey(bookingId)

    /** Case-insensitive substring match on customer name, newest first. */
    fun reviewsForCustomer(namePattern: String): List<Review> {
        val lower = namePattern.trim().lowercase()
        return reviewsByBooking.values
            .filter { it.customerName.lowercase().contains(lower) }
            .sortedByDescending { it.createdAt }
    }

    /** Overall average rating across every review, or null if there are none. */
    fun averageRating(): Double? {
        val all = reviewsByBooking.values
        if (all.isEmpty()) return null
        return all.sumOf { it.rating }.toDouble() / all.size
    }

    /** Average rating for the given customer, or null if they have no reviews. */
    fun averageRatingForCustomer(namePattern: String): Double? {
        val matches = reviewsForCustomer(namePattern)
        if (matches.isEmpty()) return null
        return matches.sumOf { it.rating }.toDouble() / matches.size
    }

    /** Count of reviews at each star rating, 1..[Review.MAX_RATING], including zero counts. */
    fun ratingDistribution(): Map<Int, Int> {
        val counts = reviewsByBooking.values.groupingBy { it.rating }.eachCount()
        return (Review.MIN_RATING..Review.MAX_RATING).associateWith { counts[it] ?: 0 }
    }

    /**
     * Reviews at or below [maxRating], newest first — the operator's
     * follow-up queue for unhappy customers. Defaults to 2 stars and
     * below.
     */
    fun lowRatedReviews(maxRating: Int = 2): List<Review> {
        require(maxRating in Review.MIN_RATING..Review.MAX_RATING) {
            "maxRating must be between ${Review.MIN_RATING} and ${Review.MAX_RATING}"
        }
        return reviewsByBooking.values
            .filter { it.rating <= maxRating }
            .sortedByDescending { it.createdAt }
    }

    /** One-line digest suitable for console output: count, average, and distribution. */
    fun summary(): String {
        val all = reviewsByBooking.values
        if (all.isEmpty()) return "No reviews yet."
        val avg = averageRating()!!
        val distribution = ratingDistribution().entries
            .sortedByDescending { it.key }
            .joinToString(", ") { (star, count) -> "$star★: $count" }
        return "${all.size} review(s), average %.2f/${Review.MAX_RATING} — $distribution".format(avg)
    }

    // ── Export ───────────────────────────────────────────────────────

    /**
     * Writes every review to [filePath] as CSV, newest first, and records
     * the export in the audit log. Mirrors the escaping convention used by
     * [BookingService.exportToCsv] so downstream tooling can parse either
     * file the same way.
     */
    fun exportToCsv(filePath: String) {
        val rows = reviewsByBooking.values.sortedByDescending { it.createdAt }
        PrintWriter(FileWriter(filePath)).use { writer ->
            writer.println("id,booking_id,customer_id,customer_name,rating,comment,created_at")
            for (r in rows) {
                writer.printf(
                    "%s,%s,%s,%s,%d,%s,%s%n",
                    escape(r.id), escape(r.bookingId), escape(r.customerId ?: ""),
                    escape(r.customerName), r.rating, escape(r.comment ?: ""), r.createdAt
                )
            }
        }
        service.auditLog.log("SYSTEM", AuditLog.Action.EXPORTED, "Exported ${rows.size} review(s) to $filePath")
    }

    private fun escape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
