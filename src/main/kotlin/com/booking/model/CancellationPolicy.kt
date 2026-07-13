package com.booking.model

/**
 * A tiered cancellation-refund policy.
 *
 * The refund a customer receives depends on how much notice they give before
 * the booking's start. Each [Tier] says "with at least this many hours of
 * notice, refund this percentage"; the engine picks the most generous tier the
 * notice qualifies for. Notice below the lowest tier's threshold — and the
 * no-notice / already-started case — falls to [noShowRefundPercent].
 *
 * Example (the shipped [DEFAULT]):
 *
 *   * ≥ 48h notice → 100% refund (free cancellation)
 *   * ≥ 24h notice →  50%
 *   * ≥  2h notice →  25%
 *   * otherwise    →   0% (no-show / last-minute)
 *
 * The policy is a pure value object; [com.booking.service.CancellationService]
 * applies it to a concrete booking and payment history.
 */
data class CancellationPolicy(
    val tiers: List<Tier>,
    val noShowRefundPercent: Int = 0
) {
    /** A single notice threshold and the refund percent it grants. */
    data class Tier(val minNoticeHours: Long, val refundPercent: Int) {
        init {
            require(minNoticeHours >= 0) { "minNoticeHours cannot be negative." }
            require(refundPercent in 0..100) { "refundPercent must be in 0..100." }
        }

        /** e.g. "≥48h → 100%". */
        fun label(): String = "≥${minNoticeHours}h → $refundPercent%"
    }

    /** Tiers held most-generous-notice first, so lookup is a simple first-match. */
    private val ordered: List<Tier> = tiers.sortedByDescending { it.minNoticeHours }

    init {
        require(tiers.isNotEmpty()) { "A policy needs at least one tier." }
        require(noShowRefundPercent in 0..100) { "noShowRefundPercent must be in 0..100." }
        // Reject duplicate thresholds — they'd make the applied tier ambiguous.
        val thresholds = tiers.map { it.minNoticeHours }
        require(thresholds.size == thresholds.toSet().size) {
            "Duplicate tier thresholds are not allowed."
        }
    }

    /**
     * The refund percent for [noticeHours] of notice. A negative value (the
     * booking start has already passed) is treated as a no-show and yields
     * [noShowRefundPercent].
     */
    fun refundPercentForNotice(noticeHours: Long): Int {
        if (noticeHours < 0) return noShowRefundPercent
        return ordered.firstOrNull { noticeHours >= it.minNoticeHours }?.refundPercent
            ?: noShowRefundPercent
    }

    /**
     * A short human-readable label for the tier that [noticeHours] lands in —
     * for surfacing "why" in the CLI. Returns "no-show" for the negative case.
     */
    fun tierLabelForNotice(noticeHours: Long): String {
        if (noticeHours < 0) return "no-show ($noShowRefundPercent%)"
        val tier = ordered.firstOrNull { noticeHours >= it.minNoticeHours }
        return tier?.label() ?: "under ${ordered.last().minNoticeHours}h ($noShowRefundPercent%)"
    }

    override fun toString(): String {
        val tierStr = ordered.joinToString(", ") { it.label() }
        return "CancellationPolicy[$tierStr, no-show → $noShowRefundPercent%]"
    }

    companion object {
        /** Sensible default: free ≥48h, 50% ≥24h, 25% ≥2h, else nothing. */
        val DEFAULT = CancellationPolicy(
            tiers = listOf(
                Tier(48, 100),
                Tier(24, 50),
                Tier(2, 25)
            ),
            noShowRefundPercent = 0
        )
    }
}
