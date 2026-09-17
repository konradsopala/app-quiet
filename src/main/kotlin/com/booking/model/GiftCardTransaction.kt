package com.booking.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * One balance movement on a [GiftCard]. The ledger is append-only: a
 * mistaken redemption is corrected by a compensating [Type.REVERSED]
 * entry, never by editing or deleting the original.
 *
 * [amount] is always positive; [type] carries the direction. Use
 * [signedAmount] when summing a ledger. [balanceAfter] is the card balance
 * *after* this entry applied, which lets a statement be rendered without
 * replaying every prior transaction.
 */
class GiftCardTransaction(
    val cardId: String,
    val type: Type,
    val amount: Double,
    val balanceAfter: Double,
    /** Set for REDEEMED / REVERSED so a booking's gift-card payments can be found. */
    val bookingId: String? = null,
    /** For REVERSED entries: the id of the REDEEMED entry being undone. */
    val reversesTransactionId: String? = null,
    val note: String? = null,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
    /** Optional id override for snapshot restore; default callers don't pass it. */
    id: String? = null
) {
    enum class Type(val credit: Boolean) {
        /** Initial load when the card is sold. */
        ISSUED(credit = true),
        /** Additional value added to an existing card. */
        RELOADED(credit = true),
        /** Value applied against a booking's quote. */
        REDEEMED(credit = false),
        /** A prior redemption returned to the card (e.g. booking cancelled). */
        REVERSED(credit = true),
        /** Remaining balance written off because the card was voided. */
        VOIDED(credit = false),
        /** Remaining balance forfeited because the card passed its expiry date. */
        EXPIRED(credit = false)
    }

    val id: String = id ?: ("gct_" + UUID.randomUUID().toString().replace("-", "").take(20))

    init {
        require(amount >= 0.0) { "Transaction amount cannot be negative, got $amount." }
        require(balanceAfter >= -GiftCard.EPSILON) { "balanceAfter cannot be negative, got $balanceAfter." }
        if (type == Type.REDEEMED) {
            require(bookingId != null) { "A REDEEMED transaction must reference a booking." }
        }
        if (type == Type.REVERSED) {
            require(reversesTransactionId != null) { "A REVERSED transaction must reference the redemption it undoes." }
        }
    }

    /** Positive for credits (value onto the card), negative for debits. */
    val signedAmount: Double
        get() = if (type.credit) amount else -amount

    override fun toString(): String {
        val sign = if (type.credit) "+" else "-"
        val booking = bookingId?.let { " booking:$it" } ?: ""
        val noteSuffix = note?.let { " — $it" } ?: ""
        return "[$occurredAt] $type $sign%.2f → %.2f$booking$noteSuffix".format(amount, balanceAfter)
    }
}
