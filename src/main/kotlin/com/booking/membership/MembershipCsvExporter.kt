package com.booking.membership

import com.booking.util.Cents
import java.io.File

/**
 * Writes the membership book out as CSV, in a layout the
 * [MembershipCsvImporter] can read back (the first three columns of the
 * memberships export are the importer's required ones).
 */
class MembershipCsvExporter(private val service: MembershipService) {

    fun plansCsv(): String = buildString {
        appendLine("plan_id,name,interval,price,setup_fee,trial_days,included_minutes,rollover_cap_percent,discount_percent,max_members,active,features")
        for (p in service.listPlans(includeRetired = true)) {
            appendLine(
                MembershipCsv.row(
                    p.id, p.name, p.interval.name,
                    Cents.format(p.priceCents).removePrefix("$"),
                    Cents.format(p.setupFeeCents).removePrefix("$"),
                    p.trialDays.toString(),
                    p.includedMinutesPerPeriod.toString(),
                    p.rolloverCapPercent.toString(),
                    p.bookingDiscountPercent.toString(),
                    p.maxMembers?.toString() ?: "",
                    p.active.toString(),
                    p.features.sorted().joinToString(";"),
                ),
            )
        }
    }.trimEnd('\n')

    fun membershipsCsv(): String = buildString {
        appendLine("customer_id,plan,start_date,status,cancel_reason,membership_id,period_start,period_end,minutes_available,outstanding")
        for (m in service.all()) {
            appendLine(
                MembershipCsv.row(
                    m.customerId,
                    service.requirePlan(m.planId).name,
                    m.startDate.toString(),
                    m.status.name,
                    m.cancelReason ?: "",
                    m.id,
                    m.currentPeriodStart.toString(),
                    m.currentPeriodEnd.toString(),
                    service.benefits.balance(m.id).toString(),
                    Cents.format(service.outstandingCents(m.id)).removePrefix("$"),
                ),
            )
        }
    }.trimEnd('\n')

    fun chargesCsv(): String = buildString {
        appendLine("charge_id,membership_id,kind,amount,status,due_date,paid_on,attempts,description")
        for (c in service.allCharges()) {
            appendLine(
                MembershipCsv.row(
                    c.id, c.membershipId, c.kind.name,
                    Cents.format(c.amountCents),
                    c.status.name, c.dueDate.toString(), c.paidOn?.toString() ?: "",
                    c.attempts.size.toString(), c.description,
                ),
            )
        }
    }.trimEnd('\n')

    fun writeTo(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content + "\n")
    }
}
