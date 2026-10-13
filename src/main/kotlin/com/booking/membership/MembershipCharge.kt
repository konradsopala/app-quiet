package com.booking.membership

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A single amount owed (or credited) on a membership.
 *
 * Charges are append-only records: a failed charge is retried by recording
 * new [attempts], and a refund is a separate [Kind.CREDIT] charge rather than
 * an edit to the original. [amountCents] is signed — credits are negative.
 */
class MembershipCharge(
    val membershipId: String,
    val kind: Kind,
    val amountCents: Long,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val description: String,
    val dueDate: LocalDate,
    id: String? = null,
) {
    enum class Kind { SETUP, RECURRING, PRORATION, CREDIT, ADJUSTMENT }

    enum class Status { PENDING, PAID, FAILED, VOID }

    data class Attempt(
        val at: LocalDateTime,
        val success: Boolean,
        val message: String,
    )

    val id: String = id ?: ("chg_" + UUID.randomUUID().toString().replace("-", "").take(14))

    var status: Status = if (amountCents <= 0) Status.PAID else Status.PENDING
        private set

    var paidOn: LocalDate? = null
        private set

    private val _attempts = mutableListOf<Attempt>()
    val attempts: List<Attempt> get() = _attempts.toList()

    /** Idempotency key sent to the payment gateway, stable across retries. */
    val idempotencyKey: String get() = "$membershipId:$id"

    val isOpen: Boolean get() = status == Status.PENDING || status == Status.FAILED

    fun recordAttempt(success: Boolean, message: String, on: LocalDate) {
        check(isOpen) { "Charge $id is $status and cannot accept attempts." }
        _attempts.add(Attempt(LocalDateTime.now(), success, message))
        if (success) {
            status = Status.PAID
            paidOn = on
        } else {
            status = Status.FAILED
        }
    }

    /** Annotate an already-settled credit with the gateway refund reference. */
    internal fun noteSettled(message: String, on: LocalDate) {
        check(amountCents <= 0) { "Only credits are settled on creation." }
        _attempts.add(Attempt(LocalDateTime.now(), true, message))
        paidOn = on
    }

    fun void(reason: String) {
        check(status != Status.PAID || amountCents <= 0) { "Charge $id is already paid; issue a credit instead." }
        status = Status.VOID
        _attempts.add(Attempt(LocalDateTime.now(), false, "voided: $reason"))
    }

    /** Days between the due date and [asOf]; negative while not yet due. */
    fun daysOverdue(asOf: LocalDate): Long = asOf.toEpochDay() - dueDate.toEpochDay()

    override fun toString(): String =
        "[$id] $kind ${com.booking.util.Cents.format(amountCents)} due $dueDate | $status | $description"
}
