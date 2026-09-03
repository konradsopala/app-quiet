package com.booking.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A single billable line on an [Invoice].
 *
 * Amounts are net (pre-tax); tax is computed per [taxCategory] when the
 * invoice is issued and stored as [Invoice.taxLines]. Negative unit prices
 * are allowed only on credit notes (adjustment lines) — the invoice itself
 * enforces that rule when the line is added.
 */
data class InvoiceLine(
    val description: String,
    val quantity: Double,
    val unitPrice: Double,
    val taxCategory: TaxCategory = TaxCategory.STANDARD
) {
    init {
        require(description.isNotBlank()) { "Line description cannot be blank." }
        require(quantity > 0.0) { "Line quantity must be positive." }
    }

    val netAmount: Double get() = quantity * unitPrice
}

/**
 * A computed tax amount for one category, frozen at issue time.
 *
 * Stored on the invoice (rather than recomputed on read) so that a later
 * change to the active [TaxProfile] never rewrites an issued document.
 */
data class TaxLine(
    val category: TaxCategory,
    val ratePercent: Double,
    val taxableAmount: Double,
    val taxAmount: Double
)

/**
 * A payment applied against an invoice — either synced from a
 * [PaymentIntent] (in which case [intentId] is set and used for
 * de-duplication) or entered manually from the CLI.
 */
data class InvoicePayment(
    val amount: Double,
    val recordedAt: LocalDateTime,
    val reference: String,
    val intentId: String? = null
)

/**
 * Invoice entity.
 *
 * Lifecycle:
 *
 * ```
 *   DRAFT ──issue()──▶ ISSUED ──payments──▶ PARTIALLY_PAID ──▶ PAID
 *     │                   │
 *     └──────void()───────┘   (void only while nothing has been paid)
 * ```
 *
 *  * Lines can only be edited in DRAFT.
 *  * [issue] snapshots the tax lines, assigns the sequential
 *    [invoiceNumber], and stamps [issueDate]/[dueDate]. From that point
 *    the document is immutable except for payment records.
 *  * OVERDUE is intentionally *not* a stored status — it is derived from
 *    [dueDate] vs. a caller-supplied "today" via [isOverdue], so the state
 *    machine never needs a clock tick to stay correct.
 *  * A credit note is a separate Invoice with [creditNoteFor] pointing at
 *    the original; its lines carry negative unit prices.
 *
 * Mutating transitions are `internal` — they are driven by
 * [com.booking.service.InvoiceService], which owns audit logging and
 * cross-entity checks.
 */
class Invoice(
    val bookingId: String,
    val customerName: String,
    val customerId: String? = null,
    val currency: String,
    /** Set only on credit notes: the id of the invoice being (partially) reversed. */
    val creditNoteFor: String? = null,
    /** Optional id override for snapshot restore; default callers don't pass it. */
    id: String? = null,
    createdAt: LocalDateTime? = null
) {
    enum class Status { DRAFT, ISSUED, PARTIALLY_PAID, PAID, VOID }

    val id: String = id ?: ("inv_" + UUID.randomUUID().toString().replace("-", "").take(20))
    val createdAt: LocalDateTime = createdAt ?: LocalDateTime.now()

    var status: Status = Status.DRAFT
        private set

    /** Assigned at issue time by the number sequence; null while DRAFT. */
    var invoiceNumber: String? = null
        private set

    var issueDate: LocalDate? = null
        private set

    var dueDate: LocalDate? = null
        private set

    /** Set when the invoice is voided; blank otherwise. */
    var voidReason: String? = null
        private set

    var notes: String? = null

    private val _lines = mutableListOf<InvoiceLine>()
    val lines: List<InvoiceLine> get() = _lines.toList()

    private val _taxLines = mutableListOf<TaxLine>()
    val taxLines: List<TaxLine> get() = _taxLines.toList()

    private val _payments = mutableListOf<InvoicePayment>()
    val payments: List<InvoicePayment> get() = _payments.toList()

    init {
        require(currency.length == 3) { "Currency must be a 3-letter ISO code." }
        require(customerName.isNotBlank()) { "Customer name cannot be blank." }
    }

    val isCreditNote: Boolean get() = creditNoteFor != null

    // ── Amounts ─────────────────────────────────────────────────────

    /** Net (pre-tax) sum of all lines. Negative on credit notes. */
    val subtotal: Double get() = _lines.sumOf { it.netAmount }

    /** Total tax across the frozen tax lines. Zero while DRAFT. */
    val taxTotal: Double get() = _taxLines.sumOf { it.taxAmount }

    /** Grand total (net + tax). */
    val total: Double get() = subtotal + taxTotal

    val amountPaid: Double get() = _payments.sumOf { it.amount }

    val balanceDue: Double get() = total - amountPaid

    // ── Draft-time editing ──────────────────────────────────────────

    internal fun addLine(line: InvoiceLine) {
        check(status == Status.DRAFT) { "Lines can only be added while the invoice is DRAFT." }
        if (!isCreditNote) {
            require(line.unitPrice >= 0.0) {
                "Negative unit prices are only allowed on credit notes."
            }
        }
        _lines.add(line)
    }

    /** Remove the line at [index]. Returns the removed line. */
    internal fun removeLine(index: Int): InvoiceLine {
        check(status == Status.DRAFT) { "Lines can only be removed while the invoice is DRAFT." }
        require(index in _lines.indices) { "No line at index $index." }
        return _lines.removeAt(index)
    }

    // ── Transitions ─────────────────────────────────────────────────

    internal fun issue(number: String, onDate: LocalDate, due: LocalDate, computedTax: List<TaxLine>) {
        check(status == Status.DRAFT) { "Only DRAFT invoices can be issued; current: $status." }
        check(_lines.isNotEmpty()) { "Cannot issue an invoice with no lines." }
        require(!due.isBefore(onDate)) { "Due date cannot precede the issue date." }
        invoiceNumber = number
        issueDate = onDate
        dueDate = due
        _taxLines.clear()
        _taxLines.addAll(computedTax)
        status = Status.ISSUED
    }

    internal fun recordPayment(payment: InvoicePayment) {
        check(status == Status.ISSUED || status == Status.PARTIALLY_PAID) {
            "Payments can only be recorded on issued invoices; current: $status."
        }
        require(payment.amount > 0.0) { "Payment amount must be positive." }
        require(payment.amount <= balanceDue + CENT_TOLERANCE) {
            "Payment of %.2f exceeds the balance due (%.2f).".format(payment.amount, balanceDue)
        }
        payment.intentId?.let { incoming ->
            require(_payments.none { it.intentId == incoming }) {
                "Payment intent $incoming is already recorded on this invoice."
            }
        }
        _payments.add(payment)
        status = if (balanceDue <= CENT_TOLERANCE) Status.PAID else Status.PARTIALLY_PAID
    }

    internal fun markVoid(reason: String) {
        check(status == Status.DRAFT || status == Status.ISSUED) {
            "Only DRAFT or unpaid ISSUED invoices can be voided; current: $status."
        }
        check(_payments.isEmpty()) {
            "This invoice has recorded payments — issue a credit note instead of voiding."
        }
        require(reason.isNotBlank()) { "A void reason is required." }
        voidReason = reason
        status = Status.VOID
    }

    /**
     * Restore full state from a persisted snapshot, bypassing transition
     * guards (mirrors [Booking.restoreState]).
     */
    internal fun restoreState(
        status: Status,
        invoiceNumber: String?,
        issueDate: LocalDate?,
        dueDate: LocalDate?,
        lines: List<InvoiceLine>,
        taxLines: List<TaxLine>,
        payments: List<InvoicePayment>,
        voidReason: String?
    ) {
        this.status = status
        this.invoiceNumber = invoiceNumber
        this.issueDate = issueDate
        this.dueDate = dueDate
        _lines.clear(); _lines.addAll(lines)
        _taxLines.clear(); _taxLines.addAll(taxLines)
        _payments.clear(); _payments.addAll(payments)
        this.voidReason = voidReason
    }

    // ── Derived queries ─────────────────────────────────────────────

    /** True if the invoice still carries a balance past its due date on [today]. */
    fun isOverdue(today: LocalDate): Boolean {
        val due = dueDate ?: return false
        if (status != Status.ISSUED && status != Status.PARTIALLY_PAID) return false
        return today.isAfter(due) && balanceDue > CENT_TOLERANCE
    }

    /** Days past due on [today]; 0 when not overdue. */
    fun daysOverdue(today: LocalDate): Long {
        if (!isOverdue(today)) return 0
        return java.time.temporal.ChronoUnit.DAYS.between(dueDate, today)
    }

    override fun toString(): String {
        val number = invoiceNumber ?: "(draft)"
        val kind = if (isCreditNote) "CREDIT NOTE" else "INVOICE"
        return "[$id] $kind $number | $customerName | booking:$bookingId | " +
            "%s %.2f (paid %.2f) | %s".format(currency, total, amountPaid, status)
    }

    companion object {
        /** Tolerance for floating-point cent comparisons, matching PaymentService. */
        const val CENT_TOLERANCE = 1e-9
    }
}
