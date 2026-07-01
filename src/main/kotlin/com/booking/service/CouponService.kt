package com.booking.service

import com.booking.model.Coupon
import com.booking.model.CouponDiscount
import java.time.LocalDate

/**
 * Registry of redeemable [Coupon]s plus a redemption path used by
 * [BookingPricer].
 *
 * Codes that aren't registered fall through to **dynamic codes** parsed
 * from the string itself:
 *
 *   * `PCT<n>` (n in 1..50) — n-percent discount
 *   * `FLAT<amount>` (amount in 1.0..200.0) — flat discount
 *
 * The dynamic codes existed in the original [BookingPricer] before this
 * service was extracted; preserving them keeps existing call sites
 * working. Dynamic redemptions do **not** increment any counter — they
 * have no registry entry to count against.
 *
 * Failed redemptions return a [RedeemFailure]; successful ones return
 * the price after discount and (for registered coupons) bump
 * [Coupon.usedCount]. Either way the caller knows the post-discount
 * price and the canonical code used.
 */
class CouponService(private val auditLog: AuditLog? = null) {

    private val registry = linkedMapOf<String, Coupon>()

    /**
     * Replace the in-memory registry. Used by snapshot restore — the
     * legacy codes auto-registered in [init] are wiped if the snapshot
     * doesn't include them, matching the snapshot's authoritative view.
     */
    internal fun replaceAll(newCoupons: List<Coupon>) {
        registry.clear()
        for (c in newCoupons) registry[c.code] = c
    }

    init {
        // Pre-register the legacy hard-coded codes so existing flows still
        // resolve them. These have no validity window and unlimited uses;
        // operators can edit them via the CLI like any other coupon.
        register(Coupon("SAVE10", CouponDiscount.Flat(10.0)))
        register(Coupon("SAVE20", CouponDiscount.Flat(20.0)))
        register(Coupon("HALF",   CouponDiscount.Percent(50)))
        register(Coupon("VIP100", CouponDiscount.Flat(100.0), requiresCustomerType = "VIP"))
    }

    // ── CRUD ────────────────────────────────────────────────────────

    fun register(coupon: Coupon): Coupon {
        require(coupon.code !in registry) { "Coupon ${coupon.code} already exists" }
        registry[coupon.code] = coupon
        auditLog?.log(coupon.code, AuditLog.Action.COUPON_REGISTERED, coupon.toString())
        return coupon
    }

    fun find(code: String): Coupon? = registry[code.trim().uppercase()]

    fun list(): List<Coupon> = registry.values.toList()

    fun delete(code: String): Boolean = registry.remove(code.trim().uppercase()) != null

    fun size(): Int = registry.size

    // ── Redemption ──────────────────────────────────────────────────

    sealed class RedeemResult {
        abstract val canonicalCode: String?
        abstract val newPrice: Double

        /** Coupon applied; [newPrice] is post-discount. */
        data class Applied(
            override val canonicalCode: String,
            override val newPrice: Double,
            val source: Source
        ) : RedeemResult() {
            enum class Source { REGISTERED, DYNAMIC_PCT, DYNAMIC_FLAT }
        }

        /** Coupon did not apply; [newPrice] equals the input price. */
        data class Rejected(
            override val canonicalCode: String?,
            override val newPrice: Double,
            val reasons: List<String>
        ) : RedeemResult()
    }

    fun tryApply(
        price: Double,
        code: String?,
        customerType: String?,
        today: LocalDate = LocalDate.now()
    ): RedeemResult {
        if (code.isNullOrBlank()) {
            return RedeemResult.Rejected(null, price, listOf("No coupon code provided."))
        }
        val normalised = code.trim().uppercase()
        val registered = registry[normalised]

        if (registered != null) {
            val reasons = registered.rejectionReasons(today, customerType)
            if (reasons.isNotEmpty()) {
                return RedeemResult.Rejected(normalised, price, reasons)
            }
            registered.usedCount += 1
            val newPrice = registered.discount.apply(price)
            auditLog?.log(
                normalised, AuditLog.Action.COUPON_REDEEMED,
                "Discount %s, %.2f → %.2f".format(registered.discount, price, newPrice)
            )
            return RedeemResult.Applied(normalised, newPrice, RedeemResult.Applied.Source.REGISTERED)
        }

        // Fall through to the dynamic codes. These never bump a counter.
        dynamicPct(normalised)?.let { pct ->
            val newPrice = CouponDiscount.Percent(pct).apply(price)
            return RedeemResult.Applied(normalised, newPrice, RedeemResult.Applied.Source.DYNAMIC_PCT)
        }
        dynamicFlat(normalised)?.let { flat ->
            val newPrice = CouponDiscount.Flat(flat).apply(price)
            return RedeemResult.Applied(normalised, newPrice, RedeemResult.Applied.Source.DYNAMIC_FLAT)
        }

        return RedeemResult.Rejected(normalised, price, listOf("Unknown coupon code."))
    }

    // ── Dynamic-code parsers ────────────────────────────────────────

    /** Returns the percent if [code] is `PCT<n>` with n in 1..50, else null. */
    private fun dynamicPct(code: String): Int? {
        if (!code.startsWith("PCT")) return null
        val n = code.substring(3).toIntOrNull() ?: return null
        return n.takeIf { it in 1..50 }
    }

    /** Returns the amount if [code] is `FLAT<amount>` with amount in 1.0..200.0, else null. */
    private fun dynamicFlat(code: String): Double? {
        if (!code.startsWith("FLAT")) return null
        val n = code.substring(4).toDoubleOrNull() ?: return null
        return n.takeIf { it in 1.0..200.0 }
    }
}
