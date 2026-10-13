package com.booking.membership

import java.time.LocalDate

/**
 * Append-only ledger of booking-minute benefits, keyed by membership id.
 *
 * The ledger itself is policy-free: it records entries and answers balance
 * queries. The period-boundary rules (how much rolls over, what expires)
 * live in [closePeriod], which is the only place that turns a leftover
 * balance into ROLLOVER / EXPIRE entries.
 */
class BenefitLedger {

    private val byMembership = linkedMapOf<String, MutableList<BenefitEntry>>()

    // ── Writes ───────────────────────────────────────────────────────

    fun grant(membershipId: String, minutes: Int, on: LocalDate, note: String = "Period allowance"): BenefitEntry =
        append(BenefitEntry(membershipId = membershipId, type = BenefitEntry.Type.GRANT, minutes = minutes,
            effectiveDate = on, reference = null, note = note))

    /**
     * Consume [minutes] against [bookingId]. Fails without writing if the
     * balance is insufficient — partial consumption is never recorded.
     */
    fun consume(membershipId: String, minutes: Int, on: LocalDate, bookingId: String): BenefitEntry {
        require(minutes > 0) { "Minutes to consume must be positive." }
        val available = balance(membershipId)
        require(available >= minutes) {
            "Insufficient benefit minutes: need $minutes, have $available."
        }
        require(!alreadyConsumed(membershipId, bookingId)) {
            "Booking $bookingId has already consumed benefit minutes."
        }
        return append(BenefitEntry(membershipId = membershipId, type = BenefitEntry.Type.CONSUME, minutes = -minutes,
            effectiveDate = on, reference = bookingId, note = "Booking $bookingId"))
    }

    /** Return the minutes a booking consumed. Returns null if nothing was consumed. */
    fun refundBooking(membershipId: String, bookingId: String, on: LocalDate): BenefitEntry? {
        val consumed = entries(membershipId)
            .filter { it.type == BenefitEntry.Type.CONSUME && it.reference == bookingId }
            .sumOf { -it.minutes }
        val refunded = entries(membershipId)
            .filter { it.type == BenefitEntry.Type.REFUND && it.reference == bookingId }
            .sumOf { it.minutes }
        val outstanding = consumed - refunded
        if (outstanding <= 0) return null
        return append(BenefitEntry(membershipId = membershipId, type = BenefitEntry.Type.REFUND, minutes = outstanding,
            effectiveDate = on, reference = bookingId, note = "Refund for cancelled booking $bookingId"))
    }

    fun adjust(membershipId: String, minutes: Int, on: LocalDate, note: String): BenefitEntry {
        require(note.isNotBlank()) { "An adjustment needs a note explaining why." }
        if (minutes < 0) {
            val available = balance(membershipId)
            require(available + minutes >= 0) { "Adjustment would drive the balance negative ($available $minutes)." }
        }
        return append(BenefitEntry(membershipId = membershipId, type = BenefitEntry.Type.ADJUST, minutes = minutes,
            effectiveDate = on, reference = null, note = note.trim()))
    }

    /**
     * Close out a period: roll over up to [rolloverCap] unused minutes and
     * expire the rest. Returns the entries written (possibly empty).
     *
     * The rollover is modelled as an EXPIRE of the entire leftover followed by
     * a ROLLOVER of the retained part, so the audit trail shows precisely what
     * was forfeited versus carried.
     */
    fun closePeriod(membershipId: String, on: LocalDate, rolloverCap: Int): List<BenefitEntry> {
        require(rolloverCap >= 0) { "Rollover cap cannot be negative." }
        val leftover = balance(membershipId)
        if (leftover <= 0) return emptyList()
        val retained = minOf(leftover, rolloverCap)
        val written = mutableListOf<BenefitEntry>()
        written += append(BenefitEntry(membershipId = membershipId, type = BenefitEntry.Type.EXPIRE, minutes = -leftover,
            effectiveDate = on, reference = null, note = "Period close: $leftover min unused"))
        if (retained > 0) {
            written += append(BenefitEntry(membershipId = membershipId, type = BenefitEntry.Type.ROLLOVER, minutes = retained,
                effectiveDate = on, reference = null, note = "Rolled over $retained of $leftover min (cap $rolloverCap)"))
        }
        return written
    }

    // ── Reads ────────────────────────────────────────────────────────

    fun entries(membershipId: String): List<BenefitEntry> = byMembership[membershipId]?.toList() ?: emptyList()

    fun balance(membershipId: String): Int = byMembership[membershipId]?.sumOf { it.minutes } ?: 0

    /** Balance as it stood at the end of [asOf] (entries after that date are ignored). */
    fun balanceAsOf(membershipId: String, asOf: LocalDate): Int =
        byMembership[membershipId]?.filter { !it.effectiveDate.isAfter(asOf) }?.sumOf { it.minutes } ?: 0

    fun totalConsumed(membershipId: String): Int =
        entries(membershipId).filter { it.type == BenefitEntry.Type.CONSUME }.sumOf { -it.minutes } -
            entries(membershipId).filter { it.type == BenefitEntry.Type.REFUND }.sumOf { it.minutes }

    fun totalGranted(membershipId: String): Int =
        entries(membershipId).filter { it.type == BenefitEntry.Type.GRANT }.sumOf { it.minutes }

    /** Minutes consumed per calendar month, oldest first. Key format `YYYY-MM`. */
    fun consumptionByMonth(membershipId: String): Map<String, Int> =
        entries(membershipId)
            .filter { it.type == BenefitEntry.Type.CONSUME || it.type == BenefitEntry.Type.REFUND }
            .groupBy { "%04d-%02d".format(it.effectiveDate.year, it.effectiveDate.monthValue) }
            .mapValues { (_, list) -> -list.sumOf { it.minutes } }
            .toSortedMap()

    /** Every membership id that has at least one entry. */
    fun membershipIds(): Set<String> = byMembership.keys.toSet()

    /** Sum of positive balances across all memberships — the outstanding minute liability. */
    fun outstandingLiability(): Int = byMembership.keys.sumOf { balance(it).coerceAtLeast(0) }

    // ── Internals ────────────────────────────────────────────────────

    private fun alreadyConsumed(membershipId: String, bookingId: String): Boolean {
        val consumed = entries(membershipId).count { it.type == BenefitEntry.Type.CONSUME && it.reference == bookingId }
        val refunded = entries(membershipId).count { it.type == BenefitEntry.Type.REFUND && it.reference == bookingId }
        return consumed > refunded
    }

    private fun append(entry: BenefitEntry): BenefitEntry {
        byMembership.getOrPut(entry.membershipId) { mutableListOf() }.add(entry)
        return entry
    }
}
