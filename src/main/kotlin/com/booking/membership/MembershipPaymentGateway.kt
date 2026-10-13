package com.booking.membership

/**
 * Pluggable payment backend for membership charges.
 *
 * Mirrors the shape of the booking `PaymentProcessor` but is deliberately
 * separate: membership billing is off-session (no customer present), so the
 * result must carry a machine-readable [Decline] reason that dunning can act on.
 */
interface MembershipPaymentGateway {

    enum class Decline(val retryable: Boolean) {
        INSUFFICIENT_FUNDS(retryable = true),
        CARD_EXPIRED(retryable = false),
        CARD_REPORTED_LOST(retryable = false),
        PROCESSOR_UNAVAILABLE(retryable = true),
        DO_NOT_HONOUR(retryable = true),
    }

    sealed class Result {
        data class Approved(val reference: String) : Result()
        data class Declined(val reason: Decline, val message: String) : Result()
    }

    /**
     * Charge [amountCents] for [customerId]. [idempotencyKey] is stable across
     * retries of the same [MembershipCharge], so a real gateway can dedupe.
     */
    fun charge(customerId: String, amountCents: Long, idempotencyKey: String): Result

    /** Return [amountCents] against an earlier approved charge. */
    fun refund(customerId: String, amountCents: Long, originalReference: String): Result
}

/**
 * Deterministic in-memory gateway for demos and tests.
 *
 * Scripted outcomes are consumed per customer in order; once a customer's
 * script is exhausted the gateway approves. [alwaysDecline] pins a customer to
 * a single failure reason indefinitely.
 */
class MockMembershipGateway : MembershipPaymentGateway {

    private val scripts = mutableMapOf<String, ArrayDeque<MembershipPaymentGateway.Decline?>>()
    private val pinned = mutableMapOf<String, MembershipPaymentGateway.Decline>()
    private val approvedByKey = mutableMapOf<String, String>()
    private var counter = 0

    val charged = mutableListOf<Triple<String, Long, String>>()
    val refunded = mutableListOf<Triple<String, Long, String>>()

    /** Queue outcomes for [customerId]: `null` = approve, otherwise decline with that reason. */
    fun script(customerId: String, vararg outcomes: MembershipPaymentGateway.Decline?) {
        scripts.getOrPut(customerId) { ArrayDeque() }.addAll(outcomes.toList())
    }

    fun alwaysDecline(customerId: String, reason: MembershipPaymentGateway.Decline) {
        pinned[customerId] = reason
    }

    fun clear(customerId: String) {
        scripts.remove(customerId)
        pinned.remove(customerId)
    }

    override fun charge(customerId: String, amountCents: Long, idempotencyKey: String): MembershipPaymentGateway.Result {
        require(amountCents > 0) { "Gateway charges must be positive." }
        approvedByKey[idempotencyKey]?.let { return MembershipPaymentGateway.Result.Approved(it) }

        pinned[customerId]?.let { return decline(it) }
        val next = scripts[customerId]?.removeFirstOrNull()
        if (next != null) return decline(next)

        counter += 1
        val ref = "mock_ch_%05d".format(counter)
        approvedByKey[idempotencyKey] = ref
        charged += Triple(customerId, amountCents, ref)
        return MembershipPaymentGateway.Result.Approved(ref)
    }

    override fun refund(customerId: String, amountCents: Long, originalReference: String): MembershipPaymentGateway.Result {
        require(amountCents > 0) { "Gateway refunds must be positive." }
        counter += 1
        val ref = "mock_re_%05d".format(counter)
        refunded += Triple(customerId, amountCents, ref)
        return MembershipPaymentGateway.Result.Approved(ref)
    }

    private fun decline(reason: MembershipPaymentGateway.Decline): MembershipPaymentGateway.Result =
        MembershipPaymentGateway.Result.Declined(reason, "Mock decline: ${reason.name.lowercase().replace('_', ' ')}")
}
