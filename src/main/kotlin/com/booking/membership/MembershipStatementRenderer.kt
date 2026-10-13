package com.booking.membership

import com.booking.util.Cents
import com.booking.util.TextTable
import java.time.LocalDate

/**
 * Produces the customer-facing statement for a membership: charges, credits,
 * benefit-minute activity and a running balance. Plain text, 72 columns wide,
 * so it can be printed, emailed or written to a file unchanged.
 */
class MembershipStatementRenderer(private val service: MembershipService) {

    fun render(membershipId: String, asOf: LocalDate): String {
        val m = service.require(membershipId)
        val plan = service.requirePlan(m.planId)
        return buildString {
            appendLine(rule('='))
            appendLine(center("MEMBERSHIP STATEMENT"))
            appendLine(rule('='))
            appendLine("Statement date : $asOf")
            appendLine("Membership     : ${m.id}")
            appendLine("Customer       : ${m.customerId}")
            appendLine("Plan           : ${plan.name} — ${Cents.format(plan.priceCents)} ${plan.interval.label.lowercase()}")
            appendLine("Status         : ${m.status}")
            appendLine("Member since   : ${m.startDate}")
            appendLine()
            appendLine("CHARGES")
            appendLine(rule('-'))
            appendLine(chargeTable(membershipId))
            appendLine()
            appendLine("BENEFIT MINUTES")
            appendLine(rule('-'))
            appendLine(benefitTable(membershipId))
            appendLine()
            appendLine(rule('-'))
            val paid = service.chargesFor(membershipId).filter { it.status == MembershipCharge.Status.PAID }.sumOf { it.amountCents }
            appendLine("Total paid (net of credits) : ${Cents.format(paid).padStart(12)}")
            appendLine("Currently outstanding       : ${Cents.format(service.outstandingCents(membershipId)).padStart(12)}")
            appendLine("Minutes available           : ${service.benefits.balance(membershipId).toString().padStart(12)}")
            append(rule('='))
        }
    }

    private fun chargeTable(membershipId: String): String {
        val charges = service.chargesFor(membershipId)
        if (charges.isEmpty()) return "  (no charges)"
        val table = TextTable(listOf("Due", "Type", "Description", "Amount", "Status"))
        table.align(3, TextTable.Align.RIGHT)
        var running = 0L
        for (c in charges.sortedBy { it.dueDate }) {
            if (c.status == MembershipCharge.Status.PAID) running += c.amountCents
            table.row(c.dueDate.toString(), c.kind.name, c.description.take(34), Cents.format(c.amountCents), c.status.name)
        }
        return table.render() + "\n  Running total of paid items: ${Cents.format(running)}"
    }

    private fun benefitTable(membershipId: String): String {
        val entries = service.benefits.entries(membershipId)
        if (entries.isEmpty()) return "  (no benefit activity)"
        val table = TextTable(listOf("Date", "Type", "Minutes", "Balance", "Note"))
        table.align(2, TextTable.Align.RIGHT).align(3, TextTable.Align.RIGHT)
        var balance = 0
        for (e in entries) {
            balance += e.minutes
            val signed = if (e.minutes > 0) "+${e.minutes}" else e.minutes.toString()
            table.row(e.effectiveDate.toString(), e.type.name, signed, balance.toString(), e.note.take(36))
        }
        return table.render()
    }

    private fun rule(ch: Char): String = ch.toString().repeat(WIDTH)

    private fun center(text: String): String {
        val pad = ((WIDTH - text.length) / 2).coerceAtLeast(0)
        return " ".repeat(pad) + text
    }

    companion object {
        private const val WIDTH = 72
    }
}
