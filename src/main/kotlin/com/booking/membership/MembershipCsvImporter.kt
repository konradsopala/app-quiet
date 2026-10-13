package com.booking.membership

import com.booking.service.AuditLog
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Bulk-enrols members from a CSV, typically when migrating from another
 * system. Expected header (column order is free, extra columns are ignored):
 *
 * ```
 * customer_id,plan,start_date[,status][,cancel_reason]
 * ```
 *
 * `status` may be `ACTIVE` (default), `PAUSED` or `CANCELLED`. Historic rows
 * (a `start_date` in the past) are enrolled as of that date without charging
 * the gateway, because the migrated member has already paid the source system.
 *
 * Every row is validated before anything is written, so a [dryRun] report is
 * exactly what a real import would do.
 */
class MembershipCsvImporter(
    private val service: MembershipService,
    private val auditLog: AuditLog? = null,
) {

    data class RowError(val line: Int, val message: String) {
        override fun toString(): String = "line $line: $message"
    }

    data class Row(
        val line: Int,
        val customerId: String,
        val plan: MembershipPlan,
        val startDate: LocalDate,
        val status: Membership.Status,
        val cancelReason: String?,
    )

    data class Result(
        val dryRun: Boolean,
        val rows: List<Row>,
        val errors: List<RowError>,
        val imported: List<Membership>,
    ) {
        val ok: Boolean get() = errors.isEmpty()

        fun render(): String = buildString {
            appendLine(if (dryRun) "Dry run: nothing was written." else "Import complete.")
            appendLine("  Valid rows:  ${rows.size}")
            appendLine("  Imported:    ${imported.size}")
            append("  Errors:      ${errors.size}")
            errors.forEach { append("\n    $it") }
        }
    }

    private val requiredColumns = listOf("customer_id", "plan", "start_date")

    /**
     * Parse and validate [csv]. When [dryRun] is false and there are no errors,
     * every valid row is imported; if any row is invalid nothing is imported.
     */
    fun import(csv: String, today: LocalDate, dryRun: Boolean = false): Result {
        val records = try {
            MembershipCsv.parse(csv)
        } catch (e: IllegalArgumentException) {
            return Result(dryRun, emptyList(), listOf(RowError(0, e.message ?: "unreadable CSV")), emptyList())
        }
        if (records.isEmpty()) return Result(dryRun, emptyList(), listOf(RowError(0, "CSV is empty")), emptyList())

        val header = records.first().map { it.trim().lowercase() }
        val missing = requiredColumns.filter { it !in header }
        if (missing.isNotEmpty()) {
            return Result(dryRun, emptyList(), listOf(RowError(1, "missing required column(s): ${missing.joinToString()}")), emptyList())
        }
        val index = header.withIndex().associate { (i, name) -> name to i }

        val errors = mutableListOf<RowError>()
        val rows = mutableListOf<Row>()
        val seen = mutableSetOf<Pair<String, String>>()

        records.drop(1).forEachIndexed { n, cells ->
            val line = n + 2
            fun cell(name: String): String = index[name]?.let { cells.getOrNull(it) }?.trim().orEmpty()
            parseRow(line, ::cell, today, seen, errors)?.let { rows += it }
        }

        if (dryRun || errors.isNotEmpty()) return Result(dryRun, rows, errors, emptyList())

        val imported = rows.map { apply(it, today) }
        auditLog?.log("import", AuditLog.Action.MEMBERSHIP_IMPORTED, "${imported.size} memberships imported")
        return Result(false, rows, errors, imported)
    }

    private fun parseRow(
        line: Int,
        cell: (String) -> String,
        today: LocalDate,
        seen: MutableSet<Pair<String, String>>,
        errors: MutableList<RowError>,
    ): Row? {
        val before = errors.size
        val customerId = cell("customer_id")
        if (customerId.isEmpty()) errors += RowError(line, "customer_id is required")

        val planName = cell("plan")
        val plan = service.findPlanByName(planName)
        if (planName.isEmpty()) errors += RowError(line, "plan is required")
        else if (plan == null) errors += RowError(line, "unknown plan '$planName'")

        val startText = cell("start_date")
        val start = try {
            LocalDate.parse(startText)
        } catch (e: DateTimeParseException) {
            errors += RowError(line, "start_date '$startText' is not a valid ISO date")
            null
        }
        if (start != null && start.isAfter(today.plusYears(1))) errors += RowError(line, "start_date is more than a year in the future")

        val statusText = cell("status").ifEmpty { "ACTIVE" }.uppercase()
        val status = IMPORTABLE_STATUSES.firstOrNull { it.name == statusText }
        if (status == null) errors += RowError(line, "status '$statusText' is not importable (use ACTIVE, PAUSED or CANCELLED)")

        if (customerId.isNotEmpty() && plan != null && !seen.add(customerId to plan.id)) {
            errors += RowError(line, "duplicate of an earlier row for $customerId on ${plan.name}")
        }
        if (customerId.isNotEmpty() && plan != null && service.findLiveMembership(customerId, plan.id) != null) {
            errors += RowError(line, "$customerId already has a live ${plan.name} membership")
        }
        if (errors.size > before || customerId.isEmpty() || plan == null || start == null || status == null) return null
        return Row(line, customerId, plan, start, status, cell("cancel_reason").ifEmpty { null })
    }

    private fun apply(row: Row, today: LocalDate): Membership {
        val m = Membership(row.customerId, row.plan.id, row.startDate)
        // Historic migrations skip the gateway; place the member in the period that contains today.
        var start = row.startDate
        var end = row.plan.interval.nextPeriodStart(start, row.startDate)
        var guard = 0
        while (!end.isAfter(today) && guard++ < 600) {
            start = end
            end = row.plan.interval.nextPeriodStart(start, row.startDate)
        }
        m.currentPeriodStart = start
        m.currentPeriodEnd = end
        service.registerImported(m)
        m.record("IMPORTED", "from CSV line ${row.line}")
        when (row.status) {
            Membership.Status.PAUSED -> m.transitionTo(Membership.Status.PAUSED, "imported as paused")
            Membership.Status.CANCELLED -> {
                m.cancelReason = row.cancelReason ?: "migrated as cancelled"
                m.transitionTo(Membership.Status.CANCELLED, m.cancelReason ?: "")
                m.endedOn = today
            }
            else -> service.grantAllowance(m, row.plan, start)
        }
        return m
    }

    companion object {
        private val IMPORTABLE_STATUSES = listOf(
            Membership.Status.ACTIVE,
            Membership.Status.PAUSED,
            Membership.Status.CANCELLED,
        )
    }
}
