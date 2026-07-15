package com.booking.service

import com.booking.config.AppConfig
import com.booking.model.Booking
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Renders a customer-facing receipt for a [CancellationService.cancel] outcome
 * and keeps a running CSV register of every receipt issued.
 *
 * This is a distinct artefact from [AuditLog]: the audit log is an internal,
 * unfiltered mutation trail and deliberately excludes raw contact details
 * (see [CancellationService.cancel]), whereas a receipt is handed to the
 * customer and is expected to carry their own name/contact info when a
 * [CustomerService] directory is wired in.
 */
class RefundReceiptExporter(
    private val service: BookingService,
    private val customers: CustomerService? = null,
    private val defaultCurrency: String = AppConfig.DEFAULT.defaultCurrency
) {

    private val issuedFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Immutable snapshot of a single issued receipt. */
    data class Receipt(
        val receiptNumber: String,
        val bookingId: String,
        val customerName: String,
        val customerId: String?,
        val issuedAt: LocalDateTime,
        val servicePeriod: String,
        val noticeLabel: String,
        val tierLabel: String,
        val currency: String,
        val chargedAmount: Double,
        val refundAmount: Double,
        val feeAmount: Double,
        val refundedIntentIds: List<String>,
        val incomplete: Boolean,
        val indeterminate: Boolean
    )

    /**
     * Build a [Receipt] from an executed [CancellationService.Result]. Pure —
     * no I/O, no audit logging — so callers can preview or re-render without
     * side effects. [currency] defaults to the exporter's configured
     * currency since [CancellationService.Quote] itself is currency-agnostic.
     */
    fun build(
        booking: Booking,
        result: CancellationService.Result,
        currency: String = defaultCurrency
    ): Receipt {
        val quote = result.quote
        val noticeLabel = if (quote.isNoShow) "after start (no-show)" else "${quote.noticeHours} h notice"
        return Receipt(
            receiptNumber = "RCPT-" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase(),
            bookingId = booking.id,
            customerName = booking.customerName,
            customerId = booking.customerId,
            issuedAt = LocalDateTime.now(),
            servicePeriod = "${booking.date} ${booking.startTime}-${booking.endTime}",
            noticeLabel = noticeLabel,
            tierLabel = quote.tierLabel,
            currency = currency,
            chargedAmount = quote.chargedAmount,
            refundAmount = quote.refundAmount,
            feeAmount = quote.feeAmount,
            refundedIntentIds = result.refunded.map { it.id },
            incomplete = result.refundFailures.isNotEmpty(),
            indeterminate = result.indeterminateFailure != null
        )
    }

    /** Render [receipt] as a plain-text document suitable for emailing or printing. */
    fun render(receipt: Receipt): String {
        val sb = StringBuilder()
        appendLine(sb, "=".repeat(42))
        appendLine(sb, "CANCELLATION REFUND RECEIPT")
        appendLine(sb, "=".repeat(42))
        appendLine(sb, "Receipt #:     ${receipt.receiptNumber}")
        appendLine(sb, "Issued:        ${receipt.issuedAt.format(issuedFmt)}")
        appendLine(sb, "Booking:       ${receipt.bookingId}")
        appendLine(sb, "Service:       ${receipt.servicePeriod}")
        appendLine(sb, "")
        appendLine(sb, "Customer:      ${receipt.customerName}")
        contactLineFor(receipt.customerId)?.let { appendLine(sb, "Contact:       $it") }
        appendLine(sb, "")
        appendLine(sb, "Notice given:  ${receipt.noticeLabel}")
        appendLine(sb, "Policy tier:   ${receipt.tierLabel}")
        appendLine(sb, "-".repeat(42))
        appendLine(sb, "Charged:       %s %.2f".format(receipt.currency, receipt.chargedAmount))
        appendLine(sb, "Refunded:      %s %.2f".format(receipt.currency, receipt.refundAmount))
        appendLine(sb, "Fee retained:  %s %.2f".format(receipt.currency, receipt.feeAmount))
        appendLine(sb, "-".repeat(42))
        if (receipt.refundedIntentIds.isEmpty()) {
            appendLine(sb, "No payment intents were refunded (unpaid or advisory-only).")
        } else {
            appendLine(sb, "Refunded payment intent(s):")
            receipt.refundedIntentIds.forEach { appendLine(sb, "  - $it") }
        }
        if (receipt.incomplete) {
            appendLine(sb, "")
            appendLine(sb, "NOTE: reimbursement is incomplete — one or more refunds failed.")
            appendLine(sb, "Please contact support referencing this receipt number.")
        }
        if (receipt.indeterminate) {
            appendLine(sb, "")
            appendLine(sb, "NOTE: one refund's outcome could not be confirmed with the processor")
            appendLine(sb, "and requires manual reconciliation. Please contact support referencing")
            appendLine(sb, "this receipt number.")
        }
        appendLine(sb, "=".repeat(42))
        return sb.toString()
    }

    /**
     * Build, render, and persist a receipt for [booking]/[result] to
     * [filePath], and record the export in the audit log. Returns the built
     * [Receipt] so the caller can also feed it to [appendToRegister].
     */
    fun save(
        booking: Booking,
        result: CancellationService.Result,
        filePath: String,
        currency: String = defaultCurrency
    ): Receipt {
        val receipt = build(booking, result, currency)
        File(filePath).writeText(render(receipt))
        val customerRef = receipt.customerId?.let { "cust:$it" } ?: "-"
        service.auditLog.log(
            booking.id, AuditLog.Action.RECEIPT_EXPORTED,
            "Receipt ${receipt.receiptNumber} exported to $filePath | customer: $customerRef"
        )
        return receipt
    }

    /**
     * Append [receipt] as one row to a running CSV register at
     * [registerPath], writing the header first if the file doesn't exist yet
     * (or is empty). Lets an operator keep a single accounting-friendly file
     * of every refund issued instead of hunting through individual receipts.
     */
    fun appendToRegister(receipt: Receipt, registerPath: String) {
        val file = File(registerPath)
        val needsHeader = !file.exists() || file.length() == 0L
        file.parentFile?.mkdirs()
        file.appendText(buildString {
            if (needsHeader) {
                append(
                    "receipt_number,booking_id,customer_id,customer_name,issued_at," +
                        "notice,tier,currency,charged,refund,fee,incomplete,indeterminate\n"
                )
            }
            append(
                listOf(
                    receipt.receiptNumber,
                    receipt.bookingId,
                    receipt.customerId ?: "",
                    escape(receipt.customerName),
                    receipt.issuedAt.format(issuedFmt),
                    escape(receipt.noticeLabel),
                    escape(receipt.tierLabel),
                    receipt.currency,
                    "%.2f".format(receipt.chargedAmount),
                    "%.2f".format(receipt.refundAmount),
                    "%.2f".format(receipt.feeAmount),
                    receipt.incomplete.toString(),
                    receipt.indeterminate.toString()
                ).joinToString(",")
            )
            append("\n")
        })
    }

    // ── internals ──────────────────────────────────────────────────

    /**
     * "email, phone" for the linked customer when a directory is wired in and
     * the customer has contact details on file; null otherwise so [render]
     * can skip the line entirely rather than print an empty "Contact:".
     */
    private fun contactLineFor(customerId: String?): String? {
        val directory = customers ?: return null
        val customer = customerId?.let { directory.find(it) } ?: return null
        val parts = listOfNotNull(customer.email, customer.phone)
        return parts.ifEmpty { null }?.joinToString(", ")
    }

    private fun appendLine(sb: StringBuilder, line: String) {
        sb.append(line).append('\n')
    }

    private fun escape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
