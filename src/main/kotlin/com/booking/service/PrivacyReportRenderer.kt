package com.booking.service

import com.booking.model.ConsentRecord
import com.booking.model.PrivacyRequest
import com.booking.util.TextTable
import java.time.LocalDate

/**
 * Console views for the privacy desk: the request queue with its
 * deadlines, a subject's consent history, the erasure register, and the
 * one-screen dashboard. Pure formatting — no data access.
 */
class PrivacyReportRenderer {

    fun renderRequests(requests: List<PrivacyRequest>, asOf: LocalDate): String {
        if (requests.isEmpty()) return "No privacy requests."
        val table = TextTable(listOf("Id", "Type", "Subject", "Channel", "Status", "Received", "Due", "Days left"))
        table.align(7, TextTable.Align.RIGHT)
        for (r in requests) {
            val daysLeft = when {
                !r.isOpen() -> "—"
                r.isOverdue(asOf) -> "OVERDUE ${-r.daysRemaining(asOf)}d"
                else -> r.daysRemaining(asOf).toString()
            }
            table.row(
                r.id, r.type.name, r.subjectId ?: "${r.subjectRef} (unmatched)", r.channel, r.status.name,
                r.receivedAt.toLocalDate().toString(), r.dueAt.toString(), daysLeft
            )
        }
        val overdue = requests.count { it.isOverdue(asOf) }
        val footer = "${requests.size} request(s), ${requests.count { it.isOpen() }} open" +
            (if (overdue > 0) ", $overdue OVERDUE" else "")
        return table.render() + "\n" + footer
    }

    fun renderRequestDetail(r: PrivacyRequest, asOf: LocalDate): String = buildString {
        appendLine("Request ${r.id}")
        appendLine("  Type:         ${r.type} — ${r.type.description}")
        appendLine("  Subject:      ${r.subjectId ?: "(unmatched)"}  (as given: ${r.subjectRef})")
        appendLine("  Channel:      ${r.channel}")
        appendLine("  Received:     ${r.receivedAt}")
        appendLine("  Due:          ${r.dueAt}" + if (r.isOverdue(asOf)) "  ⚠ OVERDUE" else if (r.isOpen()) "  (${r.daysRemaining(asOf)} days left)" else "")
        appendLine("  Status:       ${r.status}")
        r.verificationMethod?.let { appendLine("  Verified:     $it at ${r.verifiedAt}") }
        r.closedAt?.let { appendLine("  Closed:       $it") }
        r.outcome?.let { appendLine("  Outcome:      $it") }
        if (r.notes.isNotEmpty()) {
            appendLine("  Notes:")
            r.notes.forEach { appendLine("    - $it") }
        }
        appendLine("  Next steps:   ${PrivacyRequest.ALLOWED.getValue(r.status).joinToString(" / ").ifEmpty { "none (closed)" }}")
    }

    fun renderConsents(subjectLabel: String, consents: List<ConsentRecord>, asOf: LocalDate): String {
        val sb = StringBuilder("Consent held for $subjectLabel as of $asOf\n")
        val table = TextTable(listOf("Purpose", "Lawful basis", "Standing", "Recorded", "Source", "Expires", "Withdrawn"))
        for (purpose in ConsentRecord.Purpose.values()) {
            val latest = consents.filter { it.purpose == purpose }.maxByOrNull { it.recordedAt }
            val standing = when {
                !purpose.requiresConsent -> "n/a (${purpose.lawfulBasis})"
                latest == null -> "no record"
                latest.isEffectiveOn(asOf) -> "GRANTED"
                latest.withdrawnAt != null -> "withdrawn"
                !latest.granted -> "declined"
                else -> "expired"
            }
            table.row(
                purpose.name, purpose.lawfulBasis, standing,
                latest?.recordedAt?.toLocalDate()?.toString() ?: "", latest?.source ?: "",
                latest?.expiresAt?.toString() ?: "", latest?.withdrawnAt?.toLocalDate()?.toString() ?: ""
            )
        }
        sb.append(table.render())
        if (consents.size > ConsentRecord.Purpose.values().size) {
            sb.append("\n${consents.size} record(s) in total; table shows the latest per purpose.")
        }
        return sb.toString()
    }

    fun renderErasureRegister(records: List<PrivacyService.ErasureRecord>): String {
        if (records.isEmpty()) return "Erasure register is empty."
        val table = TextTable(listOf("Pseudonym", "Erased", "Request", "Bookings", "Reviews", "Waitlist", "Pay reasons", "Audit", "Invoices kept"))
        (3..8).forEach { table.align(it, TextTable.Align.RIGHT) }
        for (r in records) {
            table.row(
                r.pseudonym, r.erasedAt.toLocalDate().toString(), r.requestId,
                r.bookingsAnonymised.toString(), r.reviewsAnonymised.toString(), r.waitlistEntriesAnonymised.toString(),
                r.paymentReasonsCleared.toString(), r.auditEntriesRedacted.toString(), r.invoicesRetained.toString()
            )
        }
        return table.render()
    }

    fun renderDashboard(d: PrivacyService.Dashboard): String = buildString {
        appendLine("=== Privacy dashboard — ${d.asOf} ===")
        appendLine("Requests: ${d.openRequests} open" + (if (d.overdueRequests > 0) ", ${d.overdueRequests} OVERDUE" else "") +
            " | lifetime by type: " + d.requestsByType.entries.joinToString(", ") { "${it.value} ${it.key}" })
        appendLine("Subjects: ${d.customersTotal} customer record(s), ${d.erasedCustomers} erased; ${d.erasedSubjects} entry(ies) in the erasure register")
        appendLine()
        val table = TextTable(listOf("Purpose", "Basis", "Needs consent", "Standing grants", "Withdrawn / declined / lapsed"))
        table.align(3, TextTable.Align.RIGHT); table.align(4, TextTable.Align.RIGHT)
        for ((purpose, counts) in d.consentsByPurpose) {
            table.row(purpose.name, purpose.lawfulBasis, if (purpose.requiresConsent) "yes" else "no", counts.first.toString(), counts.second.toString())
        }
        append(table.render())
    }
}
