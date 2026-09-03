package com.booking.service

import com.booking.config.AppConfig
import com.booking.model.Booking
import com.booking.model.Invoice
import com.booking.model.InvoiceLine
import com.booking.model.InvoicePayment
import com.booking.model.PaymentIntent
import com.booking.model.TaxCategory
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Owns the invoice ledger and drives every invoice state transition.
 *
 * Design notes:
 *
 *  * An invoice is *derived from* a booking (it starts from the booking's
 *    quote) but lives independently afterwards — rescheduling or re-quoting
 *    the booking never silently rewrites an invoice. Billing corrections go
 *    through credit notes, like real accounting.
 *  * Payment reconciliation is pull-based: [syncPaymentsFromIntents] scans
 *    the booking's SUCCEEDED payment intents and records any that the
 *    invoice hasn't seen yet (de-duplicated by intent id). Manual payments
 *    (cash, bank transfer) go through [recordManualPayment].
 *  * All mutations are recorded in [BookingService.auditLog] under the
 *    booking id, so an invoice's history shows up in the booking's
 *    existing history view.
 */
class InvoiceService(
    private val service: BookingService,
    private val payments: PaymentService,
    private val taxCalculator: TaxCalculator = TaxCalculator(),
    private val sequence: InvoiceNumberSequence = InvoiceNumberSequence(),
    private val config: AppConfig = AppConfig.DEFAULT
) {

    private val invoices = linkedMapOf<String, Invoice>()

    /** Replace the ledger. Used by snapshot restore. */
    internal fun replaceAll(newInvoices: List<Invoice>) {
        invoices.clear()
        for (inv in newInvoices) invoices[inv.id] = inv
    }

    // ── Creation ────────────────────────────────────────────────────

    /**
     * Create a DRAFT invoice from [bookingId].
     *
     * The booking must be CONFIRMED and carry a quote; the quote total
     * becomes the first line. Extra billable lines (equipment hire, catering,
     * late fees) can be added while the invoice stays DRAFT.
     *
     * A booking can have at most one live (non-VOID, non-credit-note)
     * invoice — re-invoicing requires voiding the old draft or crediting
     * the old issued document first.
     */
    fun createFromBooking(
        bookingId: String,
        taxCategory: TaxCategory = TaxCategory.STANDARD,
        currency: String = config.defaultCurrency
    ): Invoice {
        val booking = service.findBooking(bookingId)
            ?: throw IllegalArgumentException("Unknown bookingId: $bookingId")
        check(booking.status == Booking.Status.CONFIRMED) {
            "Cannot invoice a cancelled booking."
        }
        val quote = booking.quote
            ?: throw IllegalStateException("Booking has no quote — quote the price first.")
        val existing = liveInvoiceForBooking(bookingId)
        check(existing == null) {
            "Booking already has invoice ${existing!!.invoiceNumber ?: existing.id} " +
                "(${existing.status}); void or credit it before re-invoicing."
        }

        val invoice = Invoice(
            bookingId = bookingId,
            customerName = booking.customerName,
            customerId = booking.customerId,
            currency = currency.uppercase()
        )
        invoice.addLine(
            InvoiceLine(
                description = bookingLineDescription(booking),
                quantity = 1.0,
                unitPrice = quote.total,
                taxCategory = taxCategory
            )
        )
        invoices[invoice.id] = invoice
        service.auditLog.log(
            bookingId, AuditLog.Action.INVOICE_CREATED,
            "Invoice: ${invoice.id}, Draft total: %s %.2f".format(invoice.currency, invoice.total)
        )
        return invoice
    }

    private fun bookingLineDescription(booking: Booking): String {
        val desc = booking.description.ifBlank { "Booking" }
        return "$desc — ${booking.date} ${booking.startTime}-${booking.endTime}"
    }

    /** Append an extra billable line to a DRAFT invoice. */
    fun addLine(invoiceId: String, line: InvoiceLine): Invoice {
        val invoice = get(invoiceId)
        invoice.addLine(line)
        service.auditLog.log(
            invoice.bookingId, AuditLog.Action.INVOICE_LINE_ADDED,
            "Invoice: ${invoice.id}, Line: ${line.description} " +
                "(%.2f x %.2f, ${line.taxCategory})".format(line.quantity, line.unitPrice)
        )
        return invoice
    }

    /** Remove line [index] (0-based) from a DRAFT invoice. */
    fun removeLine(invoiceId: String, index: Int): Invoice {
        val invoice = get(invoiceId)
        val removed = invoice.removeLine(index)
        service.auditLog.log(
            invoice.bookingId, AuditLog.Action.INVOICE_LINE_REMOVED,
            "Invoice: ${invoice.id}, Removed: ${removed.description}"
        )
        return invoice
    }

    // ── Issue / void ────────────────────────────────────────────────

    /**
     * Issue a DRAFT invoice: computes and freezes the tax lines, assigns
     * the next sequential number, and stamps issue + due dates
     * (due = issue + [AppConfig.invoiceNetDays]).
     */
    fun issue(invoiceId: String, onDate: LocalDate = LocalDate.now()): Invoice {
        val invoice = get(invoiceId)
        val taxLines = taxCalculator.computeTax(invoice.lines)
        val number = sequence.next(onDate, creditNote = invoice.isCreditNote)
        val due = onDate.plusDays(config.invoiceNetDays)
        invoice.issue(number, onDate, due, taxLines)
        service.auditLog.log(
            invoice.bookingId, AuditLog.Action.INVOICE_ISSUED,
            "Invoice: ${invoice.id}, Number: $number, Total: %s %.2f, Due: %s"
                .format(invoice.currency, invoice.total, due)
        )
        return invoice
    }

    /** Void a DRAFT or unpaid ISSUED invoice with a mandatory [reason]. */
    fun voidInvoice(invoiceId: String, reason: String): Invoice {
        val invoice = get(invoiceId)
        invoice.markVoid(reason)
        service.auditLog.log(
            invoice.bookingId, AuditLog.Action.INVOICE_VOIDED,
            "Invoice: ${invoice.id}, Number: ${invoice.invoiceNumber ?: "(draft)"}, Reason: $reason"
        )
        return invoice
    }

    // ── Payments ────────────────────────────────────────────────────

    /**
     * Pull the booking's SUCCEEDED payment intents onto the invoice.
     *
     * Each intent is recorded at its *settled* (net-of-refund) value and
     * de-duplicated by intent id, so calling this repeatedly is safe. An
     * intent whose settled value exceeds the remaining balance is recorded
     * at the remaining balance — the surplus stays visible on the payment
     * side rather than overpaying the invoice.
     *
     * Returns the payments that were newly recorded.
     */
    fun syncPaymentsFromIntents(invoiceId: String): List<InvoicePayment> {
        val invoice = get(invoiceId)
        check(invoice.status == Invoice.Status.ISSUED || invoice.status == Invoice.Status.PARTIALLY_PAID) {
            "Payments can only be synced onto an issued invoice; current: ${invoice.status}."
        }
        val alreadySeen = invoice.payments.mapNotNull { it.intentId }.toSet()
        val candidates = payments.listForBooking(invoice.bookingId)
            .filter { it.status == PaymentIntent.Status.SUCCEEDED }
            .filter { it.id !in alreadySeen }

        val recorded = mutableListOf<InvoicePayment>()
        for (intent in candidates) {
            if (invoice.balanceDue <= Invoice.CENT_TOLERANCE) break
            val settled = intent.remainingRefundable
            if (settled <= Invoice.CENT_TOLERANCE) continue
            val applied = minOf(settled, invoice.balanceDue)
            val payment = InvoicePayment(
                amount = applied,
                recordedAt = LocalDateTime.now(),
                reference = intent.processorReference ?: intent.id,
                intentId = intent.id
            )
            invoice.recordPayment(payment)
            recorded.add(payment)
            service.auditLog.log(
                invoice.bookingId, AuditLog.Action.INVOICE_PAYMENT_RECORDED,
                "Invoice: ${invoice.id}, Intent: ${intent.id}, Applied: %s %.2f, Balance: %.2f"
                    .format(invoice.currency, applied, invoice.balanceDue)
            )
        }
        if (invoice.status == Invoice.Status.PAID) {
            service.auditLog.log(
                invoice.bookingId, AuditLog.Action.INVOICE_PAID,
                "Invoice: ${invoice.id}, Number: ${invoice.invoiceNumber}, Total: %s %.2f"
                    .format(invoice.currency, invoice.total)
            )
        }
        return recorded
    }

    /** Record an out-of-band payment (cash, transfer) against an issued invoice. */
    fun recordManualPayment(invoiceId: String, amount: Double, reference: String): Invoice {
        val invoice = get(invoiceId)
        require(reference.isNotBlank()) { "A payment reference is required." }
        invoice.recordPayment(
            InvoicePayment(amount = amount, recordedAt = LocalDateTime.now(), reference = reference)
        )
        service.auditLog.log(
            invoice.bookingId, AuditLog.Action.INVOICE_PAYMENT_RECORDED,
            "Invoice: ${invoice.id}, Manual: %s %.2f (%s), Balance: %.2f"
                .format(invoice.currency, amount, reference, invoice.balanceDue)
        )
        if (invoice.status == Invoice.Status.PAID) {
            service.auditLog.log(
                invoice.bookingId, AuditLog.Action.INVOICE_PAID,
                "Invoice: ${invoice.id}, Number: ${invoice.invoiceNumber}, Total: %s %.2f"
                    .format(invoice.currency, invoice.total)
            )
        }
        return invoice
    }

    // ── Credit notes ────────────────────────────────────────────────

    /**
     * Issue a credit note reversing part (or all) of an issued invoice.
     *
     * The credit note is created and *immediately issued* (credit notes have
     * no useful draft stage here): a single negative line for [amount],
     * carrying the original invoice's dominant tax category so the tax
     * reversal tracks the original charge.
     *
     * [amount] must be positive and cannot exceed what has not already been
     * credited across earlier credit notes for the same invoice.
     */
    fun issueCreditNote(
        invoiceId: String,
        amount: Double,
        reason: String,
        onDate: LocalDate = LocalDate.now()
    ): Invoice {
        val original = get(invoiceId)
        check(!original.isCreditNote) { "Cannot issue a credit note against a credit note." }
        check(
            original.status == Invoice.Status.ISSUED ||
                original.status == Invoice.Status.PARTIALLY_PAID ||
                original.status == Invoice.Status.PAID
        ) { "Credit notes require an issued invoice; current: ${original.status}." }
        require(amount > 0.0) { "Credit amount must be positive." }
        require(reason.isNotBlank()) { "A credit reason is required." }

        val alreadyCredited = creditNotesFor(invoiceId).sumOf { -it.subtotal }
        val creditable = original.subtotal - alreadyCredited
        require(amount <= creditable + Invoice.CENT_TOLERANCE) {
            "Credit of %.2f exceeds the remaining creditable net %.2f."
                .format(amount, creditable)
        }

        val creditNote = Invoice(
            bookingId = original.bookingId,
            customerName = original.customerName,
            customerId = original.customerId,
            currency = original.currency,
            creditNoteFor = original.id
        )
        creditNote.addLine(
            InvoiceLine(
                description = "Credit against ${original.invoiceNumber}: $reason",
                quantity = 1.0,
                unitPrice = -amount,
                taxCategory = dominantTaxCategory(original)
            )
        )
        invoices[creditNote.id] = creditNote
        issue(creditNote.id, onDate)
        service.auditLog.log(
            original.bookingId, AuditLog.Action.CREDIT_NOTE_ISSUED,
            "Credit note ${creditNote.invoiceNumber} for ${original.invoiceNumber}: " +
                "%s %.2f — %s".format(creditNote.currency, amount, reason)
        )
        return creditNote
    }

    /** The tax category carrying the largest net amount on [invoice]. */
    private fun dominantTaxCategory(invoice: Invoice): TaxCategory =
        invoice.lines
            .groupBy { it.taxCategory }
            .maxByOrNull { (_, lines) -> lines.sumOf { it.netAmount } }
            ?.key ?: TaxCategory.STANDARD

    // ── Queries ─────────────────────────────────────────────────────

    private fun get(invoiceId: String): Invoice =
        invoices[invoiceId] ?: throw IllegalArgumentException("Unknown invoiceId: $invoiceId")

    fun find(invoiceId: String): Invoice? = invoices[invoiceId]

    /** Find by id *or* human-readable invoice number (case-insensitive). */
    fun resolve(idOrNumber: String): Invoice? {
        invoices[idOrNumber]?.let { return it }
        return invoices.values.firstOrNull {
            it.invoiceNumber?.equals(idOrNumber.trim(), ignoreCase = true) == true
        }
    }

    fun list(): List<Invoice> = invoices.values.toList()

    fun listForBooking(bookingId: String): List<Invoice> =
        invoices.values.filter { it.bookingId == bookingId }

    fun creditNotesFor(invoiceId: String): List<Invoice> =
        invoices.values.filter { it.creditNoteFor == invoiceId }

    /** The booking's current non-void, non-credit-note invoice, if any. */
    fun liveInvoiceForBooking(bookingId: String): Invoice? =
        invoices.values.firstOrNull {
            it.bookingId == bookingId && !it.isCreditNote && it.status != Invoice.Status.VOID
        }

    /** Issued invoices still carrying a balance past their due date on [today]. */
    fun overdue(today: LocalDate = LocalDate.now()): List<Invoice> =
        invoices.values
            .filter { it.isOverdue(today) }
            .sortedByDescending { it.daysOverdue(today) }

    /** Sum of all open balances across issued, unpaid/partially-paid invoices. */
    fun totalOutstanding(): Double =
        invoices.values
            .filter { it.status == Invoice.Status.ISSUED || it.status == Invoice.Status.PARTIALLY_PAID }
            .sumOf { it.balanceDue }

    // ── Aging report ────────────────────────────────────────────────

    /**
     * Classic accounts-receivable aging buckets, keyed by a human label.
     * "Current" holds not-yet-due balances; the rest bucket by days overdue.
     */
    data class AgingBucket(val label: String, val invoices: List<Invoice>, val balance: Double)

    fun agingReport(today: LocalDate = LocalDate.now()): List<AgingBucket> {
        val open = invoices.values.filter {
            it.status == Invoice.Status.ISSUED || it.status == Invoice.Status.PARTIALLY_PAID
        }
        val buckets = listOf(
            "Current" to 0L..0L,
            "1-30 days" to 1L..30L,
            "31-60 days" to 31L..60L,
            "61-90 days" to 61L..90L,
            "90+ days" to 91L..Long.MAX_VALUE
        )
        return buckets.map { (label, range) ->
            val matching = open.filter { inv ->
                val days = inv.daysOverdue(today)
                if (label == "Current") !inv.isOverdue(today) else days in range
            }
            AgingBucket(label, matching, matching.sumOf { it.balanceDue })
        }
    }
}
