package com.booking.service

import com.booking.model.Invoice
import com.booking.model.TaxProfile
import com.booking.util.TextTable
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Renders invoices for humans (fixed-width console documents, list tables,
 * the aging report) and machines (CSV export).
 *
 * Pure presentation — nothing here mutates an invoice. The renderer takes
 * the [TaxProfile] only to print the registration note in the document
 * footer; all amounts come from the frozen tax lines on the invoice itself.
 */
class InvoiceRenderer(
    private val businessName: String = "Booking Manager",
    private val taxProfile: TaxProfile = TaxProfile.DEFAULT
) {

    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE

    // ── Full document ───────────────────────────────────────────────

    /**
     * Render one invoice as a printable fixed-width document:
     * header, bill-to block, line table, tax breakdown, totals, and the
     * payment history. Width is fixed at [DOC_WIDTH] columns.
     */
    fun renderDocument(invoice: Invoice, today: LocalDate = LocalDate.now()): String {
        val sb = StringBuilder()
        val title = if (invoice.isCreditNote) "CREDIT NOTE" else "INVOICE"
        val rule = "=".repeat(DOC_WIDTH)
        val thinRule = "-".repeat(DOC_WIDTH)

        sb.appendLine(rule)
        sb.appendLine(center(businessName))
        sb.appendLine(center(title))
        sb.appendLine(rule)

        // Header block
        sb.appendLine(kv("Number", invoice.invoiceNumber ?: "(draft — not yet issued)"))
        sb.appendLine(kv("Status", statusLabel(invoice, today)))
        invoice.issueDate?.let { sb.appendLine(kv("Issued", it.format(dateFormat))) }
        invoice.dueDate?.let { sb.appendLine(kv("Due", it.format(dateFormat))) }
        invoice.creditNoteFor?.let { sb.appendLine(kv("Credits invoice", it)) }
        sb.appendLine(kv("Booking", invoice.bookingId))
        sb.appendLine(thinRule)

        // Bill-to
        sb.appendLine("Bill to: ${invoice.customerName}")
        invoice.customerId?.let { sb.appendLine("         (customer $it)") }
        sb.appendLine(thinRule)

        // Lines
        val lineTable = TextTable(listOf("#", "Description", "Qty", "Unit", "Net", "Tax cat"))
            .align(0, TextTable.Align.RIGHT)
            .align(2, TextTable.Align.RIGHT)
            .align(3, TextTable.Align.RIGHT)
            .align(4, TextTable.Align.RIGHT)
        invoice.lines.forEachIndexed { i, line ->
            lineTable.row(
                (i + 1).toString(),
                truncate(line.description, 40),
                trimQty(line.quantity),
                money(line.unitPrice),
                money(line.netAmount),
                line.taxCategory.name
            )
        }
        sb.appendLine(lineTable.render().trimEnd())
        sb.appendLine(thinRule)

        // Tax breakdown (frozen at issue; empty on drafts)
        if (invoice.taxLines.isNotEmpty()) {
            for (tax in invoice.taxLines) {
                sb.appendLine(
                    amountRow(
                        "Tax ${tax.category.name} (%.3f%% of %s)"
                            .format(tax.ratePercent, money(tax.taxableAmount)),
                        tax.taxAmount
                    )
                )
            }
        } else {
            sb.appendLine("  (tax is computed when the invoice is issued)")
        }
        sb.appendLine(amountRow("Subtotal", invoice.subtotal))
        sb.appendLine(amountRow("Tax total", invoice.taxTotal))
        sb.appendLine(amountRow("TOTAL ${invoice.currency}", invoice.total))
        sb.appendLine(thinRule)

        // Payments
        if (invoice.payments.isEmpty()) {
            sb.appendLine("No payments recorded.")
        } else {
            sb.appendLine("Payments:")
            for (p in invoice.payments) {
                val source = p.intentId?.let { "intent $it" } ?: "manual"
                sb.appendLine(
                    "  %s  %s  (%s, ref %s)".format(
                        p.recordedAt.toLocalDate().format(dateFormat),
                        money(p.amount), source, p.reference
                    )
                )
            }
            sb.appendLine(amountRow("Paid", invoice.amountPaid))
            sb.appendLine(amountRow("Balance due", invoice.balanceDue))
        }

        invoice.voidReason?.let {
            sb.appendLine(thinRule)
            sb.appendLine("VOIDED: $it")
        }
        invoice.notes?.let {
            sb.appendLine(thinRule)
            sb.appendLine("Notes: $it")
        }

        sb.appendLine(rule)
        taxProfile.registrationNote?.let { sb.appendLine(center(it)) }
        sb.append(center("Generated ${today.format(dateFormat)} — thank you for your business"))
        return sb.toString()
    }

    private fun statusLabel(invoice: Invoice, today: LocalDate): String =
        if (invoice.isOverdue(today)) {
            "${invoice.status} (OVERDUE ${invoice.daysOverdue(today)} days)"
        } else {
            invoice.status.toString()
        }

    // ── List table ──────────────────────────────────────────────────

    /** Compact one-row-per-invoice table for the "list invoices" menu. */
    fun renderList(invoices: List<Invoice>, today: LocalDate = LocalDate.now()): String {
        if (invoices.isEmpty()) return "No invoices."
        val table = TextTable(
            listOf("Number", "Kind", "Customer", "Status", "Total", "Paid", "Balance", "Due")
        )
            .align(4, TextTable.Align.RIGHT)
            .align(5, TextTable.Align.RIGHT)
            .align(6, TextTable.Align.RIGHT)
        for (inv in invoices) {
            table.row(
                inv.invoiceNumber ?: "(draft)",
                if (inv.isCreditNote) "CN" else "INV",
                truncate(inv.customerName, 24),
                statusLabel(inv, today),
                money(inv.total),
                money(inv.amountPaid),
                money(inv.balanceDue),
                inv.dueDate?.format(dateFormat) ?: "-"
            )
        }
        return table.render()
    }

    // ── Aging report ────────────────────────────────────────────────

    fun renderAgingReport(
        buckets: List<InvoiceService.AgingBucket>,
        today: LocalDate = LocalDate.now()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Accounts receivable aging — as of ${today.format(dateFormat)}")
        sb.appendLine()
        val table = TextTable(listOf("Bucket", "Invoices", "Balance"))
            .align(1, TextTable.Align.RIGHT)
            .align(2, TextTable.Align.RIGHT)
        var grandTotal = 0.0
        for (bucket in buckets) {
            table.row(bucket.label, bucket.invoices.size.toString(), money(bucket.balance))
            grandTotal += bucket.balance
        }
        table.row("TOTAL", buckets.sumOf { it.invoices.size }.toString(), money(grandTotal))
        sb.append(table.render())
        return sb.toString()
    }

    // ── CSV export ──────────────────────────────────────────────────

    /**
     * Export [invoices] to [path] as CSV, one row per invoice, with
     * RFC 4180-style quoting for embedded commas/quotes/newlines.
     *
     * @return the number of data rows written
     * @throws IOException if the file cannot be written
     */
    @Throws(IOException::class)
    fun exportCsv(invoices: List<Invoice>, path: String, today: LocalDate = LocalDate.now()): Int {
        val header = listOf(
            "id", "number", "kind", "bookingId", "customer", "status",
            "issueDate", "dueDate", "currency",
            "subtotal", "taxTotal", "total", "paid", "balance",
            "daysOverdue", "creditNoteFor"
        )
        val rows = invoices.map { inv ->
            listOf(
                inv.id,
                inv.invoiceNumber ?: "",
                if (inv.isCreditNote) "CREDIT_NOTE" else "INVOICE",
                inv.bookingId,
                inv.customerName,
                inv.status.toString(),
                inv.issueDate?.toString() ?: "",
                inv.dueDate?.toString() ?: "",
                inv.currency,
                "%.2f".format(inv.subtotal),
                "%.2f".format(inv.taxTotal),
                "%.2f".format(inv.total),
                "%.2f".format(inv.amountPaid),
                "%.2f".format(inv.balanceDue),
                inv.daysOverdue(today).toString(),
                inv.creditNoteFor ?: ""
            )
        }
        val csv = buildString {
            appendLine(header.joinToString(",") { csvEscape(it) })
            for (row in rows) appendLine(row.joinToString(",") { csvEscape(it) })
        }
        File(path).writeText(csv)
        return rows.size
    }

    private fun csvEscape(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }

    // ── Formatting helpers ──────────────────────────────────────────

    private fun money(amount: Double): String = "%,.2f".format(amount)

    /** Quantities print without a trailing ".0" when whole. */
    private fun trimQty(qty: Double): String =
        if (qty == qty.toLong().toDouble()) qty.toLong().toString() else "%.2f".format(qty)

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    private fun center(text: String): String {
        val gap = ((DOC_WIDTH - text.length) / 2).coerceAtLeast(0)
        return " ".repeat(gap) + text
    }

    private fun kv(key: String, value: String): String =
        "%-16s %s".format("$key:", value)

    private fun amountRow(label: String, amount: Double): String {
        val amountText = money(amount)
        val gap = (DOC_WIDTH - label.length - amountText.length - 2).coerceAtLeast(1)
        return "$label:" + " ".repeat(gap) + amountText
    }

    companion object {
        const val DOC_WIDTH = 72
    }
}
