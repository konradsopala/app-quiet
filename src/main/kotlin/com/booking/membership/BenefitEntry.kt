package com.booking.membership

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * One row in the append-only benefit-minutes ledger.
 *
 * [minutes] is signed: grants, rollovers and refunds are positive; consumption
 * and expiry are negative. The balance of a membership is the sum of its
 * entries, so nothing is ever edited in place.
 */
data class BenefitEntry(
    val id: String = "ben_" + UUID.randomUUID().toString().replace("-", "").take(12),
    val membershipId: String,
    val type: Type,
    val minutes: Int,
    val effectiveDate: LocalDate,
    val reference: String?,
    val note: String,
    val recordedAt: LocalDateTime = LocalDateTime.now(),
) {
    enum class Type(val sign: Int) {
        /** Period allowance granted at the start of a billing period. */
        GRANT(+1),

        /** Unused minutes carried over from the previous period. */
        ROLLOVER(+1),

        /** Minutes used by a booking. */
        CONSUME(-1),

        /** Minutes returned because a booking was cancelled or shortened. */
        REFUND(+1),

        /** Minutes forfeited at period end (beyond the rollover cap). */
        EXPIRE(-1),

        /** Manual correction by staff; sign is carried by [minutes]. */
        ADJUST(0),
    }

    init {
        when (type) {
            Type.ADJUST -> require(minutes != 0) { "An adjustment of zero minutes is meaningless." }
            else -> require(minutes > 0 == (type.sign > 0) && minutes != 0) {
                "$type entries must carry ${if (type.sign > 0) "positive" else "negative"} minutes."
            }
        }
    }

    override fun toString(): String {
        val signed = if (minutes > 0) "+$minutes" else minutes.toString()
        val ref = reference?.let { " ref:$it" } ?: ""
        return "$effectiveDate $type $signed min$ref — $note"
    }
}
