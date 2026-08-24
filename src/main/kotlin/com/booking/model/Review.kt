package com.booking.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Post-service customer feedback attached to a single [Booking].
 *
 * A review is only meaningful once the booked service has actually
 * happened, and [com.booking.service.ReviewService] enforces the rules
 * that make that true: the booking must be [Booking.Status.CONFIRMED]
 * and its date must not be in the future, and a booking may carry at
 * most one review. This class itself only validates the review's own
 * shape (rating range, comment length) — it has no knowledge of the
 * booking it's attached to beyond the id.
 */
class Review(
    val bookingId: String,
    val customerName: String,
    rating: Int,
    comment: String? = null,
    val customerId: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val id: String = "rev_" + UUID.randomUUID().toString().replace("-", "").take(16)
) {
    companion object {
        const val MIN_RATING = 1
        const val MAX_RATING = 5
        const val MAX_COMMENT_LENGTH = 500
    }

    val rating: Int = rating

    /** Trimmed; blank comments collapse to `null` so callers don't have to special-case whitespace. */
    val comment: String? = comment?.trim()?.takeIf { it.isNotEmpty() }

    init {
        require(rating in MIN_RATING..MAX_RATING) {
            "rating must be between $MIN_RATING and $MAX_RATING, got $rating"
        }
        require(comment == null || comment.trim().length <= MAX_COMMENT_LENGTH) {
            "comment cannot exceed $MAX_COMMENT_LENGTH characters"
        }
        require(customerName.isNotBlank()) { "customerName cannot be blank" }
    }

    override fun toString(): String {
        val stars = "*".repeat(rating) + "-".repeat(MAX_RATING - rating)
        val commentSuffix = comment?.let { " | \"$it\"" } ?: ""
        return "[$id] $customerName rated booking $bookingId $stars ($rating/$MAX_RATING)$commentSuffix"
    }
}
