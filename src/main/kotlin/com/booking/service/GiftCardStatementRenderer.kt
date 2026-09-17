package com.booking.service

import com.booking.model.GiftCard
import com.booking.model.GiftCardTransaction
import com.booking.util.TextTable
import java.time.LocalDate

/**
 * Console renderers for the gift-card subsystem: a per-card statement
 * (header + ledger table) and a liability report across all cards.
 *
 * Kept separate from [GiftCardService] so the service stays free of
 * presentation concerns and the renderer can be reused by a future file
 * export or notification body.
 */
class GiftCardStatementRenderer(private val today: () -> LocalDate = { LocalDate.now() }) {

    /** Header block plus a chronological ledger table for one card. */
    fun renderStatement(card: GiftCard, transactions: List<GiftCardTransaction>): String {
        val sb = StringBuilder()
        sb.appendLine("Gift card ${card.code}")
        sb.appendLine("  Status:      ${card.status}${expiryHint(card)}")
        sb.appendLine("  Balance:     %.2f %s (of %.2f issued)".format(card.balance, card.currency, card.initialValue))
        card.recipientName?.let { sb.appendLine("  Recipient:   $it${card.recipientEmail?.let { e -> " <$e>" } ?: ""}") }
        card.purchaserCustomerId?.let { sb.appendLine("  Purchaser:   $it") }
        card.message?.let { sb.appendLine("  Message:     \"$it\"") }
        sb.appendLine("  Issued:      ${card.issuedAt}")
        sb.appendLine("  Expires:     ${card.expiresAt ?: "never"}")
        card.voidReason?.let { sb.appendLine("  Void reason: $it (${card.voidedAt})") }

        if (transactions.isEmpty()) {
            sb.appendLine("  (no transactions)")
            return sb.toString().trimEnd()
        }

        val table = TextTable(listOf("When", "Type", "Amount", "Balance", "Booking", "Note"))
            .align(2, TextTable.Align.RIGHT)
            .align(3, TextTable.Align.RIGHT)
        for (t in transactions.sortedBy { it.occurredAt }) {
            table.row(
                t.occurredAt.toString().replace('T', ' ').take(16),
                t.type.name,
                "%s%.2f".format(if (t.type.credit) "+" else "-", t.amount),
                "%.2f".format(t.balanceAfter),
                t.bookingId ?: "",
                t.note ?: ""
            )
        }
        sb.appendLine()
        sb.append(table.render())
        return sb.toString().trimEnd()
    }

    /** One row per card, for the list menu. */
    fun renderCardTable(cards: List<GiftCard>): String {
        if (cards.isEmpty()) return "No gift cards."
        val now = today()
        val table = TextTable(listOf("Code", "Status", "Balance", "Issued value", "Recipient", "Expires"))
            .align(2, TextTable.Align.RIGHT)
            .align(3, TextTable.Align.RIGHT)
        for (c in cards) {
            table.row(
                c.code,
                c.status.name,
                "%.2f %s".format(c.balance, c.currency),
                "%.2f".format(c.initialValue),
                c.recipientName ?: "",
                c.expiresAt?.let { "$it${soonMarker(c, now)}" } ?: "never"
            )
        }
        return table.render()
    }

    /**
     * The accountant's view: per currency, how many cards are live, how
     * much could still be spent, how much has been sold in total, and how
     * much has been forfeited to expiry. Followed by the soon-to-expire
     * list so the operator can prompt customers.
     */
    fun renderLiabilityReport(service: GiftCardService, expiryWindowDays: Long = 30): String {
        val liability = service.outstandingLiability()
        val sold = service.totalSold()
        val breakage = service.breakage()
        val currencies = (liability.keys + sold.keys + breakage.keys).toSortedSet()
        val sb = StringBuilder()
        sb.appendLine("Gift card liability as of ${today()}")
        sb.appendLine(service.summary())
        if (currencies.isEmpty()) return sb.toString().trimEnd()

        val table = TextTable(listOf("Currency", "Live cards", "Outstanding", "Total sold", "Breakage", "Redeemed %"))
            .align(1, TextTable.Align.RIGHT)
            .align(2, TextTable.Align.RIGHT)
            .align(3, TextTable.Align.RIGHT)
            .align(4, TextTable.Align.RIGHT)
            .align(5, TextTable.Align.RIGHT)
        for (cur in currencies) {
            val live = service.list().count { it.currency == cur && it.isRedeemableOn(today()) }
            val out = liability[cur] ?: 0.0
            val soldAmt = sold[cur] ?: 0.0
            val brk = breakage[cur] ?: 0.0
            val redeemedPct = if (soldAmt > 0.0) (soldAmt - out - brk) / soldAmt * 100.0 else 0.0
            table.row(
                cur, live.toString(), "%.2f".format(out), "%.2f".format(soldAmt),
                "%.2f".format(brk), "%.1f%%".format(redeemedPct)
            )
        }
        sb.appendLine()
        sb.appendLine(table.render())

        val expiring = service.expiringWithin(expiryWindowDays)
        if (expiring.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Expiring within $expiryWindowDays days:")
            expiring.forEach { c ->
                sb.appendLine("  ${c.code}  %.2f %s  expires ${c.expiresAt} (${c.daysUntilExpiry(today())}d)".format(c.balance, c.currency))
            }
        }
        return sb.toString().trimEnd()
    }

    private fun expiryHint(card: GiftCard): String {
        val days = card.daysUntilExpiry(today()) ?: return ""
        return when {
            card.status.terminal -> ""
            days < 0 -> " (past expiry — will be swept)"
            days <= 30 -> " (expires in ${days}d)"
            else -> ""
        }
    }

    private fun soonMarker(card: GiftCard, now: LocalDate): String {
        val days = card.daysUntilExpiry(now) ?: return ""
        return if (!card.status.terminal && days in 0..30) " !" else ""
    }
}
