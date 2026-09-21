package com.booking.service

import com.booking.config.AppConfig
import com.booking.model.Booking
import com.booking.model.GiftCard
import com.booking.model.GiftCardTransaction
import com.booking.util.GiftCardCodeGenerator
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Stored-value gift cards: issue, reload, redeem against booking quotes,
 * reverse, void, and expire — with an append-only ledger and the
 * accounting views (outstanding liability, breakage) an operator needs.
 *
 * Money rules enforced here rather than in the model:
 *
 *   * A card can only be redeemed against a `CONFIRMED` booking that has a
 *     quote, in the card's own currency, and never for more than the
 *     booking still owes once earlier gift-card redemptions are netted out.
 *   * Reloads may not push the balance past [AppConfig.maxGiftCardValue];
 *     that cap is the business's exposure limit per card.
 *   * `EXPIRED` and `VOIDED` are terminal. Expiry is applied lazily by
 *     [expireDue] (called before every redemption) so a card that lapsed
 *     while the app was closed still refuses at the till.
 *   * Nothing is ever deleted. Corrections are compensating transactions.
 *
 * [today] is injectable so tests can pin the calendar.
 */
class GiftCardService(
    private val service: BookingService,
    private val customers: CustomerService,
    private val config: AppConfig = AppConfig.DEFAULT,
    private val today: () -> LocalDate = { LocalDate.now() }
) {

    class GiftCardException(message: String) : RuntimeException(message)

    /** Outcome of a successful [redeem]: what moved, and what the booking still owes. */
    data class Redemption(
        val card: GiftCard,
        val transaction: GiftCardTransaction,
        val remainingDue: Double
    ) {
        override fun toString(): String =
            "Redeemed %.2f %s from ${card.code}; card balance %.2f, booking still owes %.2f"
                .format(transaction.amount, card.currency, card.balance, remainingDue)
    }

    private val cardsById = linkedMapOf<String, GiftCard>()
    private val cardIdByCode = hashMapOf<String, String>()
    private val ledger = mutableListOf<GiftCardTransaction>()

    // ── Issue & reload ────────────────────────────────────────────────

    /**
     * Sell a new card loaded with [amount]. The expiry defaults to
     * [AppConfig.giftCardExpiryMonths] from today; pass `null` explicitly
     * via [neverExpires] for a perpetual card.
     */
    fun issue(
        amount: Double,
        currency: String = config.defaultCurrency,
        purchaserCustomerId: String? = null,
        recipientName: String? = null,
        recipientEmail: String? = null,
        message: String? = null,
        expiresAt: LocalDate? = today().plusMonths(config.giftCardExpiryMonths),
        neverExpires: Boolean = false
    ): GiftCard {
        requireAmountInRange(amount, "Gift card value")
        purchaserCustomerId?.let {
            if (customers.find(it) == null) throw GiftCardException("Unknown customer id '$it'.")
        }

        val code = generateUniqueCode()
        val card = try {
            GiftCard(
                code = code,
                initialValue = round2(amount),
                currency = currency.uppercase(),
                purchaserCustomerId = purchaserCustomerId,
                recipientName = recipientName?.trim()?.takeIf { it.isNotEmpty() },
                recipientEmail = recipientEmail?.trim()?.takeIf { it.isNotEmpty() },
                message = message,
                expiresAt = if (neverExpires) null else expiresAt
            )
        } catch (e: IllegalArgumentException) {
            throw GiftCardException(e.message ?: "Invalid gift card.")
        }

        cardsById[card.id] = card
        cardIdByCode[card.code] = card.id
        record(card, GiftCardTransaction.Type.ISSUED, card.initialValue, note = "Card sold")
        service.auditLog.log(
            "SYSTEM", AuditLog.Action.GIFT_CARD_ISSUED,
            "Issued ${card.code} for %.2f %s".format(card.initialValue, card.currency) +
                (card.recipientName?.let { " to $it" } ?: "") +
                (card.expiresAt?.let { ", expires $it" } ?: ", never expires")
        )
        return card
    }

    /**
     * Add [amount] to an existing card. A `DEPLETED` card comes back to
     * `ACTIVE`; a terminal card is rejected.
     */
    fun reload(code: String, amount: Double): GiftCard {
        requireAmountInRange(amount, "Reload amount")
        val card = requireCard(code)
        expireIfDue(card)
        if (card.status.terminal) {
            throw GiftCardException("Cannot reload ${card.code}: it is ${card.status}.")
        }
        val newBalance = round2(card.balance + amount)
        if (newBalance > config.maxGiftCardValue + GiftCard.EPSILON) {
            throw GiftCardException(
                "Reload would take ${card.code} to %.2f, above the %.2f per-card limit."
                    .format(newBalance, config.maxGiftCardValue)
            )
        }
        card.balance = newBalance
        card.status = GiftCard.Status.ACTIVE
        record(card, GiftCardTransaction.Type.RELOADED, round2(amount), note = "Reload")
        service.auditLog.log(
            "SYSTEM", AuditLog.Action.GIFT_CARD_RELOADED,
            "Reloaded ${card.code} by %.2f %s → balance %.2f".format(amount, card.currency, card.balance)
        )
        return card
    }

    // ── Redeem & reverse ──────────────────────────────────────────────

    /**
     * Apply value from the card identified by [code] to [booking]. With no
     * [amount] the service takes the most it can: the lesser of the card
     * balance and what the booking still owes.
     */
    fun redeem(code: String, booking: Booking, amount: Double? = null): Redemption {
        val card = requireCard(code)
        expireIfDue(card)

        if (booking.status != Booking.Status.CONFIRMED) {
            throw GiftCardException("Cannot redeem against booking ${booking.id}: it is ${booking.status}.")
        }
        if (booking.quote == null) {
            throw GiftCardException("Booking ${booking.id} has no quote to redeem against.")
        }
        if (!card.isRedeemableOn(today())) {
            throw GiftCardException(describeUnredeemable(card))
        }
        // Quotes don't carry a currency yet — pricing always runs in the system default.
        if (card.currency != config.defaultCurrency) {
            throw GiftCardException(
                "Card ${card.code} is in ${card.currency}; bookings are quoted in ${config.defaultCurrency}."
            )
        }

        val outstanding = outstandingForBooking(booking)
        if (outstanding <= GiftCard.EPSILON) {
            throw GiftCardException("Booking ${booking.id} is already fully covered by gift cards.")
        }

        val applied = round2(amount ?: minOf(card.balance, outstanding))
        if (applied <= 0.0) throw GiftCardException("Redemption amount must be positive.")
        if (applied > card.balance + GiftCard.EPSILON) {
            throw GiftCardException(
                "Card ${card.code} only has %.2f available, cannot redeem %.2f.".format(card.balance, applied)
            )
        }
        if (applied > outstanding + GiftCard.EPSILON) {
            throw GiftCardException(
                "Booking ${booking.id} only owes %.2f, cannot redeem %.2f.".format(outstanding, applied)
            )
        }

        card.balance = round2(card.balance - applied)
        if (card.balance <= GiftCard.EPSILON) {
            card.balance = 0.0
            card.status = GiftCard.Status.DEPLETED
        }
        val tx = record(
            card, GiftCardTransaction.Type.REDEEMED, applied,
            bookingId = booking.id, note = "Applied to ${booking.customerName}'s booking"
        )
        service.auditLog.log(
            booking.id, AuditLog.Action.GIFT_CARD_REDEEMED,
            "Redeemed %.2f %s from ${card.code} (balance now %.2f)".format(applied, card.currency, card.balance)
        )
        return Redemption(card, tx, round2(outstanding - applied))
    }

    /**
     * Undo a single redemption, returning its value to the card. Refused
     * if the card has since reached a terminal state (voided or expired,
     * where the write-off already happened) or the redemption was already
     * reversed.
     */
    fun reverse(transactionId: String, reason: String = "Reversal"): GiftCardTransaction {
        val original = ledger.firstOrNull { it.id == transactionId }
            ?: throw GiftCardException("No gift card transaction with id '$transactionId'.")
        if (original.type != GiftCardTransaction.Type.REDEEMED) {
            throw GiftCardException("Transaction $transactionId is ${original.type}, only REDEEMED entries can be reversed.")
        }
        if (ledger.any { it.type == GiftCardTransaction.Type.REVERSED && it.reversesTransactionId == transactionId }) {
            throw GiftCardException("Transaction $transactionId has already been reversed.")
        }
        val card = cardsById[original.cardId]
            ?: throw GiftCardException("Card ${original.cardId} for transaction $transactionId no longer exists.")
        if (card.status.terminal) {
            throw GiftCardException("Cannot reverse onto ${card.code}: it is ${card.status}.")
        }

        card.balance = round2(card.balance + original.amount)
        if (card.status == GiftCard.Status.DEPLETED) card.status = GiftCard.Status.ACTIVE
        val tx = record(
            card, GiftCardTransaction.Type.REVERSED, original.amount,
            bookingId = original.bookingId, reversesTransactionId = original.id, note = reason
        )
        service.auditLog.log(
            original.bookingId ?: "SYSTEM", AuditLog.Action.GIFT_CARD_REVERSED,
            "Returned %.2f %s to ${card.code}: $reason".format(original.amount, card.currency)
        )
        return tx
    }

    /**
     * Reverse every un-reversed redemption attached to [bookingId]. Used
     * when a booking is cancelled so the customer's card is made whole.
     * Cards that can't take the value back (voided) are skipped and
     * reported rather than failing the whole batch.
     */
    fun reverseAllForBooking(bookingId: String, reason: String = "Booking cancelled"): BulkReversal {
        val reversed = mutableListOf<GiftCardTransaction>()
        val skipped = mutableListOf<String>()
        for (tx in openRedemptionsForBooking(bookingId)) {
            try {
                reversed += reverse(tx.id, reason)
            } catch (e: GiftCardException) {
                skipped += "${tx.id}: ${e.message}"
            }
        }
        return BulkReversal(reversed, skipped)
    }

    data class BulkReversal(val reversed: List<GiftCardTransaction>, val skipped: List<String>) {
        val returnedTotal: Double get() = reversed.sumOf { it.amount }
    }

    // ── Void & expire ─────────────────────────────────────────────────

    /** Permanently disable a card, writing off whatever balance remains. */
    fun void(code: String, reason: String): GiftCard {
        val card = requireCard(code)
        if (card.status == GiftCard.Status.VOIDED) {
            throw GiftCardException("${card.code} is already voided.")
        }
        val trimmedReason = reason.trim()
        if (trimmedReason.isEmpty()) throw GiftCardException("A void reason is required.")

        val writtenOff = card.balance
        card.balance = 0.0
        card.status = GiftCard.Status.VOIDED
        card.voidedAt = LocalDateTime.now()
        card.voidReason = trimmedReason
        record(card, GiftCardTransaction.Type.VOIDED, writtenOff, note = trimmedReason)
        service.auditLog.log(
            "SYSTEM", AuditLog.Action.GIFT_CARD_VOIDED,
            "Voided ${card.code}, wrote off %.2f %s: $trimmedReason".format(writtenOff, card.currency)
        )
        return card
    }

    /**
     * Move every card whose expiry date has passed to `EXPIRED`, forfeiting
     * its balance. Idempotent — already-terminal cards are untouched.
     * Returns the cards expired by this call.
     */
    fun expireDue(asOf: LocalDate = today()): List<GiftCard> =
        cardsById.values.filter { !it.status.terminal && it.isExpiredOn(asOf) }
            .onEach { expire(it) }

    private fun expireIfDue(card: GiftCard) {
        if (!card.status.terminal && card.isExpiredOn(today())) expire(card)
    }

    private fun expire(card: GiftCard) {
        val forfeited = card.balance
        card.balance = 0.0
        card.status = GiftCard.Status.EXPIRED
        record(card, GiftCardTransaction.Type.EXPIRED, forfeited, note = "Expired ${card.expiresAt}")
        service.auditLog.log(
            "SYSTEM", AuditLog.Action.GIFT_CARD_EXPIRED,
            "${card.code} expired on ${card.expiresAt}, forfeited %.2f %s".format(forfeited, card.currency)
        )
    }

    // ── Queries ───────────────────────────────────────────────────────

    /** Look a card up by whatever the operator typed; null when unknown or malformed. */
    fun find(code: String): GiftCard? {
        val canonical = GiftCardCodeGenerator.normalize(code) ?: return null
        return cardIdByCode[canonical]?.let { cardsById[it] }
    }

    fun findById(id: String): GiftCard? = cardsById[id]

    fun list(): List<GiftCard> = cardsById.values.toList()

    fun listByStatus(status: GiftCard.Status): List<GiftCard> = cardsById.values.filter { it.status == status }

    fun listForCustomer(customerId: String): List<GiftCard> =
        cardsById.values.filter { it.purchaserCustomerId == customerId }

    /** Cards still redeemable that lapse within [days] of today — the "nudge the customer" list. */
    fun expiringWithin(days: Long, asOf: LocalDate = today()): List<GiftCard> =
        cardsById.values.filter { card ->
            card.isRedeemableOn(asOf) && card.expiresAt != null && !card.expiresAt.isAfter(asOf.plusDays(days))
        }.sortedBy { it.expiresAt }

    fun transactionsFor(cardId: String): List<GiftCardTransaction> = ledger.filter { it.cardId == cardId }

    fun allTransactions(): List<GiftCardTransaction> = ledger.toList()

    /** REDEEMED entries for [bookingId] that haven't been reversed. */
    fun openRedemptionsForBooking(bookingId: String): List<GiftCardTransaction> {
        val reversedIds = ledger
            .filter { it.type == GiftCardTransaction.Type.REVERSED }
            .mapNotNull { it.reversesTransactionId }
            .toSet()
        return ledger.filter {
            it.type == GiftCardTransaction.Type.REDEEMED && it.bookingId == bookingId && it.id !in reversedIds
        }
    }

    /** Net gift-card value currently applied to [bookingId]. */
    fun amountRedeemedForBooking(bookingId: String): Double =
        round2(openRedemptionsForBooking(bookingId).sumOf { it.amount })

    /** What the booking still owes after gift-card redemptions; 0 when unquoted. */
    fun outstandingForBooking(booking: Booking): Double {
        val total = booking.quote?.total ?: return 0.0
        return round2((total - amountRedeemedForBooking(booking.id)).coerceAtLeast(0.0))
    }

    fun size(): Int = cardsById.size

    // ── Accounting views ──────────────────────────────────────────────

    /**
     * Outstanding liability per currency: the sum of balances on cards a
     * customer could still walk in and spend. Expired/voided balances are
     * excluded because they've already been written off.
     */
    fun outstandingLiability(asOf: LocalDate = today()): Map<String, Double> =
        cardsById.values
            .filter { it.isRedeemableOn(asOf) }
            .groupBy { it.currency }
            .mapValues { (_, cards) -> round2(cards.sumOf { it.balance }) }
            .toSortedMap()

    /** Value forfeited through expiry (the accountant's "breakage"), per currency. */
    fun breakage(): Map<String, Double> =
        ledger.filter { it.type == GiftCardTransaction.Type.EXPIRED }
            .groupBy { cardsById[it.cardId]?.currency ?: config.defaultCurrency }
            .mapValues { (_, txs) -> round2(txs.sumOf { it.amount }) }
            .toSortedMap()

    /** Total value ever sold (ISSUED + RELOADED), per currency. */
    fun totalSold(): Map<String, Double> =
        ledger.filter { it.type == GiftCardTransaction.Type.ISSUED || it.type == GiftCardTransaction.Type.RELOADED }
            .groupBy { cardsById[it.cardId]?.currency ?: config.defaultCurrency }
            .mapValues { (_, txs) -> round2(txs.sumOf { it.amount }) }
            .toSortedMap()

    fun countByStatus(): Map<GiftCard.Status, Int> {
        val counts = cardsById.values.groupingBy { it.status }.eachCount()
        return GiftCard.Status.values().associateWith { counts[it] ?: 0 }
    }

    /** One-line digest for console output. */
    fun summary(): String {
        if (cardsById.isEmpty()) return "No gift cards issued."
        val statuses = countByStatus().entries.joinToString(", ") { (s, n) -> "$n $s" }
        val liability = outstandingLiability().entries.joinToString(", ") { (cur, amt) -> "%.2f $cur".format(amt) }
            .ifEmpty { "0.00" }
        return "${cardsById.size} card(s): $statuses | outstanding liability $liability"
    }

    // ── Export ────────────────────────────────────────────────────────

    /** Cards, one row each, newest first. */
    fun exportToCsv(filePath: String) {
        val rows = cardsById.values.sortedByDescending { it.issuedAt }
        PrintWriter(FileWriter(filePath)).use { writer ->
            writer.println(
                "id,code,currency,initial_value,balance,status,purchaser_customer_id,recipient_name," +
                    "recipient_email,issued_at,expires_at,voided_at,void_reason"
            )
            for (c in rows) {
                writer.printf(
                    Locale.ROOT,
                    "%s,%s,%s,%.2f,%.2f,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    escape(c.id), escape(c.code), escape(c.currency), c.initialValue, c.balance, c.status,
                    escape(c.purchaserCustomerId ?: ""), escape(c.recipientName ?: ""),
                    escape(c.recipientEmail ?: ""), c.issuedAt, c.expiresAt ?: "",
                    c.voidedAt ?: "", escape(c.voidReason ?: "")
                )
            }
        }
        service.auditLog.log("SYSTEM", AuditLog.Action.EXPORTED, "Exported ${rows.size} gift card(s) to $filePath")
    }

    /** The full ledger in chronological order. */
    fun exportLedgerToCsv(filePath: String) {
        PrintWriter(FileWriter(filePath)).use { writer ->
            writer.println("id,card_id,card_code,type,signed_amount,balance_after,booking_id,reverses,note,occurred_at")
            for (t in ledger) {
                writer.printf(
                    Locale.ROOT,
                    "%s,%s,%s,%s,%.2f,%.2f,%s,%s,%s,%s%n",
                    escape(t.id), escape(t.cardId), escape(cardsById[t.cardId]?.code ?: ""), t.type,
                    t.signedAmount, t.balanceAfter, escape(t.bookingId ?: ""),
                    escape(t.reversesTransactionId ?: ""), escape(t.note ?: ""), t.occurredAt
                )
            }
        }
        service.auditLog.log("SYSTEM", AuditLog.Action.EXPORTED, "Exported ${ledger.size} gift card transaction(s) to $filePath")
    }

    // ── Snapshot support ──────────────────────────────────────────────

    /** Replace all in-memory state. Used by snapshot restore. */
    internal fun replaceAll(cards: List<GiftCard>, transactions: List<GiftCardTransaction>) {
        cardsById.clear()
        cardIdByCode.clear()
        ledger.clear()
        cards.forEach {
            cardsById[it.id] = it
            cardIdByCode[it.code] = it.id
        }
        ledger.addAll(transactions)
    }

    // ── Internals ─────────────────────────────────────────────────────

    private fun requireCard(code: String): GiftCard =
        find(code) ?: throw GiftCardException(
            if (GiftCardCodeGenerator.isValid(code)) "No gift card with code '${GiftCardCodeGenerator.normalize(code)}'."
            else "'$code' is not a valid gift card code."
        )

    private fun requireAmountInRange(amount: Double, label: String) {
        if (amount.isNaN() || amount <= 0.0) throw GiftCardException("$label must be positive.")
        if (amount < config.minGiftCardValue) {
            throw GiftCardException("$label must be at least %.2f.".format(config.minGiftCardValue))
        }
        if (amount > config.maxGiftCardValue) {
            throw GiftCardException("$label cannot exceed %.2f.".format(config.maxGiftCardValue))
        }
    }

    private fun describeUnredeemable(card: GiftCard): String = when {
        card.status == GiftCard.Status.VOIDED -> "Card ${card.code} was voided" +
            (card.voidReason?.let { ": $it" } ?: ".")
        card.status == GiftCard.Status.EXPIRED || card.isExpiredOn(today()) ->
            "Card ${card.code} expired on ${card.expiresAt}."
        card.balance <= GiftCard.EPSILON -> "Card ${card.code} has no remaining balance."
        else -> "Card ${card.code} cannot be redeemed (status ${card.status})."
    }

    private fun generateUniqueCode(): String {
        repeat(MAX_CODE_ATTEMPTS) {
            val candidate = GiftCardCodeGenerator.generate()
            if (candidate !in cardIdByCode) return candidate
        }
        throw GiftCardException("Could not generate a unique gift card code after $MAX_CODE_ATTEMPTS attempts.")
    }

    private fun record(
        card: GiftCard,
        type: GiftCardTransaction.Type,
        amount: Double,
        bookingId: String? = null,
        reversesTransactionId: String? = null,
        note: String? = null
    ): GiftCardTransaction {
        val tx = GiftCardTransaction(
            cardId = card.id,
            type = type,
            amount = amount,
            balanceAfter = card.balance,
            bookingId = bookingId,
            reversesTransactionId = reversesTransactionId,
            note = note
        )
        ledger.add(tx)
        return tx
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun escape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    private companion object {
        const val MAX_CODE_ATTEMPTS = 20
    }
}
