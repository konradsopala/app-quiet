package com.booking.persistence

import com.booking.model.Booking
import com.booking.model.Coupon
import com.booking.model.CouponDiscount
import com.booking.model.Customer
import com.booking.model.PaymentIntent
import com.booking.model.Quote
import com.booking.model.Resource
import com.booking.model.WaitlistEntry
import com.booking.persistence.JsonValue.Companion.arr
import com.booking.persistence.JsonValue.Companion.obj
import com.booking.persistence.JsonValue.Companion.stringOrNull
import com.booking.service.AuditLog
import com.booking.service.BookingService
import com.booking.service.CouponService
import com.booking.service.CustomerService
import com.booking.service.PaymentService
import com.booking.service.WaitlistService
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Save and load a full snapshot of the booking system's in-memory
 * state to/from a single JSON file.
 *
 * Persisted entities:
 *   * bookings   — full schema including tags, notes, customerId, resourceId,
 *                  status, and attached quote
 *   * customers  — directory records, including createdAt for fidelity
 *   * resources  — registry including the system default
 *   * coupons    — registry plus per-coupon usedCount so redemption limits
 *                  survive restarts
 *   * waitlist   — pending entries in their original queue order
 *   * payments   — payment intents in their last-known state
 *   * auditLog   — full event log so historical queries keep working
 *
 * Loading replaces the in-memory state of each service wholesale.
 * Schema-version mismatches raise [InvalidSnapshotException] so the
 * caller can decide whether to roll forward or fail.
 */
class SnapshotStore(
    private val service: BookingService,
    private val customers: CustomerService,
    private val coupons: CouponService,
    private val payments: PaymentService,
    private val waitlist: WaitlistService
) {

    class InvalidSnapshotException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    companion object {
        const val SCHEMA_VERSION = 1
    }

    // ── Save ────────────────────────────────────────────────────────

    fun save(filePath: String) {
        val tree = encodeAll()
        File(filePath).writeText(JsonWriter().write(tree))
        service.auditLog.log(
            "SYSTEM", AuditLog.Action.SNAPSHOT_SAVED,
            "Saved snapshot to $filePath (${tree.entries.values.sumOf { (it as? JsonValue.JsonArray)?.size ?: 0 }} records)"
        )
    }

    /** Encode the live in-memory state to a single JSON object (no file IO). */
    fun encodeAll(): JsonValue.JsonObject = obj(
        "schemaVersion" to JsonValue.JsonNumber(SCHEMA_VERSION),
        "bookings"   to arr(service.listBookings().map(::encodeBooking)),
        "customers"  to arr(customers.list().map(::encodeCustomer)),
        "resources"  to arr(service.resources.list().map(::encodeResource)),
        "coupons"    to arr(coupons.list().map(::encodeCoupon)),
        "waitlist"   to arr(waitlist.list().map(::encodeWaitlistEntry)),
        "payments"   to arr(payments.list().map(::encodePaymentIntent)),
        "auditLog"   to arr(service.auditLog.getAll().map(::encodeAuditEntry))
    )

    // ── Load ────────────────────────────────────────────────────────

    fun load(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            throw InvalidSnapshotException("Snapshot file not found: $filePath")
        }
        val text = file.readText()
        val tree = try {
            JsonParser(text).parse()
        } catch (e: JsonParser.JsonParseException) {
            throw InvalidSnapshotException("Snapshot file is not valid JSON: ${e.message}", e)
        }
        restoreFrom(tree)
        service.auditLog.log(
            "SYSTEM", AuditLog.Action.SNAPSHOT_LOADED,
            "Loaded snapshot from $filePath"
        )
    }

    /** Restore from an already-parsed JSON tree. Useful for in-memory tests. */
    fun restoreFrom(root: JsonValue) {
        val rootObj = root as? JsonValue.JsonObject
            ?: throw InvalidSnapshotException("Snapshot root must be an object, got ${root::class.simpleName}")
        val version = (rootObj["schemaVersion"] as? JsonValue.JsonNumber)?.toInt()
            ?: throw InvalidSnapshotException("Snapshot is missing schemaVersion")
        if (version != SCHEMA_VERSION) {
            throw InvalidSnapshotException("Unsupported snapshot version: $version (expected $SCHEMA_VERSION)")
        }

        // Decode each section into in-memory model objects.
        val bookings  = rootObj.array("bookings").items.map { decodeBooking(it as JsonValue.JsonObject) }
        val customerL = rootObj.array("customers").items.map { decodeCustomer(it as JsonValue.JsonObject) }
        val resources = rootObj.array("resources").items.map { decodeResource(it as JsonValue.JsonObject) }
        val couponL   = rootObj.array("coupons").items.map { decodeCoupon(it as JsonValue.JsonObject) }
        val waitlistL = rootObj.array("waitlist").items.map { decodeWaitlistEntry(it as JsonValue.JsonObject) }
        val paymentL  = rootObj.array("payments").items.map { decodePaymentIntent(it as JsonValue.JsonObject) }
        val auditL    = rootObj.array("auditLog").items.map { decodeAuditEntry(it as JsonValue.JsonObject) }

        // Apply to the live services. Order: resources first (bookings may
        // reference them), then customers, then bookings, then everything else.
        service.resources.replaceAll(resources)
        customers.replaceAll(customerL)
        service.replaceBookings(bookings)
        coupons.replaceAll(couponL)
        waitlist.replaceAll(waitlistL)
        payments.replaceAll(paymentL)
        service.auditLog.replaceAll(auditL)
    }

    // ── Encoders ─────────────────────────────────────────────────────

    private fun encodeBooking(b: Booking): JsonValue.JsonObject = obj(
        "id" to JsonValue.JsonString(b.id),
        "customerName" to JsonValue.JsonString(b.customerName),
        "date" to JsonValue.JsonString(b.date.toString()),
        "startTime" to JsonValue.JsonString(b.startTime.toString()),
        "durationMinutes" to JsonValue.JsonNumber(b.durationMinutes),
        "description" to JsonValue.JsonString(b.description),
        "seriesId" to stringOrNull(b.seriesId),
        "tags" to arr(b.tags.sorted().map { JsonValue.JsonString(it) }),
        "notes" to stringOrNull(b.notes),
        "internalReference" to stringOrNull(b.internalReference),
        "customerId" to stringOrNull(b.customerId),
        "resourceId" to stringOrNull(b.resourceId),
        "status" to JsonValue.JsonString(b.status.name),
        "quote" to (b.quote?.let { encodeQuote(it) } ?: JsonValue.JsonNull)
    )

    private fun encodeQuote(q: Quote): JsonValue.JsonObject = obj(
        "total" to JsonValue.JsonNumber(q.total),
        "customerType" to JsonValue.JsonString(q.customerType),
        "partySize" to JsonValue.JsonNumber(q.partySize),
        "loyaltyYears" to JsonValue.JsonNumber(q.loyaltyYears),
        "couponCode" to stringOrNull(q.couponCode),
        "prepay" to JsonValue.JsonBoolean(q.prepay),
        "season" to JsonValue.JsonString(q.season),
        "quotedAt" to JsonValue.JsonString(q.quotedAt.toString())
    )

    private fun encodeCustomer(c: Customer): JsonValue.JsonObject = obj(
        "id" to JsonValue.JsonString(c.id),
        "name" to JsonValue.JsonString(c.name),
        "email" to stringOrNull(c.email),
        "phone" to stringOrNull(c.phone),
        "loyaltyYears" to JsonValue.JsonNumber(c.loyaltyYears),
        "notes" to JsonValue.JsonString(c.notes),
        "createdAt" to JsonValue.JsonString(c.createdAt.toString())
    )

    private fun encodeResource(r: Resource): JsonValue.JsonObject = obj(
        "id" to JsonValue.JsonString(r.id),
        "name" to JsonValue.JsonString(r.name),
        "capacity" to JsonValue.JsonNumber(r.capacity)
    )

    private fun encodeCoupon(c: Coupon): JsonValue.JsonObject = obj(
        "code" to JsonValue.JsonString(c.code),
        "discount" to encodeDiscount(c.discount),
        "validFrom" to (c.validFrom?.let { JsonValue.JsonString(it.toString()) } ?: JsonValue.JsonNull),
        "validUntil" to (c.validUntil?.let { JsonValue.JsonString(it.toString()) } ?: JsonValue.JsonNull),
        "maxUses" to (c.maxUses?.let { JsonValue.JsonNumber(it) } ?: JsonValue.JsonNull),
        "usedCount" to JsonValue.JsonNumber(c.usedCount),
        "requiresCustomerType" to stringOrNull(c.requiresCustomerType)
    )

    private fun encodeDiscount(d: CouponDiscount): JsonValue.JsonObject = when (d) {
        is CouponDiscount.Flat    -> obj("type" to JsonValue.JsonString("FLAT"), "amount" to JsonValue.JsonNumber(d.amount))
        is CouponDiscount.Percent -> obj("type" to JsonValue.JsonString("PERCENT"), "percent" to JsonValue.JsonNumber(d.percent))
    }

    private fun encodeWaitlistEntry(e: WaitlistEntry): JsonValue.JsonObject = obj(
        "id" to JsonValue.JsonString(e.id),
        "customerName" to JsonValue.JsonString(e.customerName),
        "date" to JsonValue.JsonString(e.date.toString()),
        "startTime" to JsonValue.JsonString(e.startTime.toString()),
        "durationMinutes" to JsonValue.JsonNumber(e.durationMinutes),
        "description" to JsonValue.JsonString(e.description),
        "priority" to JsonValue.JsonString(e.priority.name),
        "addedAt" to JsonValue.JsonString(e.addedAt.toString())
    )

    private fun encodePaymentIntent(p: PaymentIntent): JsonValue.JsonObject = obj(
        "id" to JsonValue.JsonString(p.id),
        "bookingId" to JsonValue.JsonString(p.bookingId),
        "amount" to JsonValue.JsonNumber(p.amount),
        "currency" to JsonValue.JsonString(p.currency),
        "status" to JsonValue.JsonString(p.status.name),
        "processorReference" to stringOrNull(p.processorReference),
        "failureReason" to stringOrNull(p.failureReason),
        "createdAt" to JsonValue.JsonString(p.createdAt.toString()),
        "settledAt" to (p.settledAt?.let { JsonValue.JsonString(it.toString()) } ?: JsonValue.JsonNull),
        "refundedAmount" to JsonValue.JsonNumber(p.refundedAmount)
    )

    private fun encodeAuditEntry(e: AuditLog.Entry): JsonValue.JsonObject = obj(
        "timestamp" to JsonValue.JsonString(e.timestamp.toString()),
        "bookingId" to JsonValue.JsonString(e.bookingId),
        "action" to JsonValue.JsonString(e.action.name),
        "detail" to JsonValue.JsonString(e.detail)
    )

    // ── Decoders ─────────────────────────────────────────────────────

    private fun decodeBooking(o: JsonValue.JsonObject): Booking {
        val tags = o.array("tags").items.map { (it as JsonValue.JsonString).value }.toSet()
        val booking = Booking(
            customerName = o.string("customerName"),
            date = LocalDate.parse(o.string("date")),
            startTime = LocalTime.parse(o.string("startTime")),
            durationMinutes = o.int("durationMinutes"),
            description = o.string("description"),
            seriesId = o.stringOrNull("seriesId"),
            tags = tags,
            notes = o.stringOrNull("notes"),
            internalReference = o.stringOrNull("internalReference"),
            customerId = o.stringOrNull("customerId"),
            resourceId = o.stringOrNull("resourceId"),
            id = o.string("id")
        )
        val status = Booking.Status.valueOf(o.string("status"))
        val quote = (o["quote"] as? JsonValue.JsonObject)?.let { decodeQuote(it) }
        booking.restoreState(status, quote)
        return booking
    }

    private fun decodeQuote(o: JsonValue.JsonObject): Quote = Quote(
        total = o.double("total"),
        customerType = o.string("customerType"),
        partySize = o.int("partySize"),
        loyaltyYears = o.int("loyaltyYears"),
        couponCode = o.stringOrNull("couponCode"),
        prepay = o.bool("prepay"),
        season = o.string("season"),
        quotedAt = LocalDateTime.parse(o.string("quotedAt"))
    )

    private fun decodeCustomer(o: JsonValue.JsonObject): Customer = Customer(
        name = o.string("name"),
        email = o.stringOrNull("email"),
        phone = o.stringOrNull("phone"),
        loyaltyYears = o.int("loyaltyYears"),
        notes = o.string("notes"),
        id = o.string("id"),
        createdAt = LocalDateTime.parse(o.string("createdAt"))
    )

    private fun decodeResource(o: JsonValue.JsonObject): Resource = Resource(
        name = o.string("name"),
        capacity = o.int("capacity"),
        id = o.string("id")
    )

    private fun decodeCoupon(o: JsonValue.JsonObject): Coupon {
        val coupon = Coupon(
            code = o.string("code"),
            discount = decodeDiscount(o.obj("discount")),
            validFrom = o.stringOrNull("validFrom")?.let { LocalDate.parse(it) },
            validUntil = o.stringOrNull("validUntil")?.let { LocalDate.parse(it) },
            maxUses = (o["maxUses"] as? JsonValue.JsonNumber)?.toInt(),
            requiresCustomerType = o.stringOrNull("requiresCustomerType")
        )
        // usedCount is internal-set on Coupon; the package-private setter is
        // visible here since SnapshotStore lives one package over.
        coupon.usedCount = o.int("usedCount")
        return coupon
    }

    private fun decodeDiscount(o: JsonValue.JsonObject): CouponDiscount =
        when (val type = o.string("type")) {
            "FLAT"    -> CouponDiscount.Flat(o.double("amount"))
            "PERCENT" -> CouponDiscount.Percent(o.int("percent"))
            else -> throw InvalidSnapshotException("Unknown discount type: $type")
        }

    private fun decodeWaitlistEntry(o: JsonValue.JsonObject): WaitlistEntry = WaitlistEntry(
        customerName = o.string("customerName"),
        date = LocalDate.parse(o.string("date")),
        startTime = LocalTime.parse(o.string("startTime")),
        durationMinutes = o.int("durationMinutes"),
        description = o.string("description"),
        priority = WaitlistEntry.Priority.valueOf(o.string("priority")),
        id = o.string("id"),
        addedAt = LocalDateTime.parse(o.string("addedAt"))
    )

    private fun decodePaymentIntent(o: JsonValue.JsonObject): PaymentIntent {
        val intent = PaymentIntent(
            bookingId = o.string("bookingId"),
            amount = o.double("amount"),
            currency = o.string("currency"),
            createdAt = LocalDateTime.parse(o.string("createdAt")),
            id = o.string("id")
        )
        intent.status = PaymentIntent.Status.valueOf(o.string("status"))
        intent.processorReference = o.stringOrNull("processorReference")
        intent.failureReason = o.stringOrNull("failureReason")
        intent.settledAt = o.stringOrNull("settledAt")?.let { LocalDateTime.parse(it) }
        // Optional — absent in snapshots written before partial refunds existed. A
        // REFUNDED intent with no explicit value predates that field entirely, so
        // it must default to the full amount, not 0 — otherwise remainingRefundable
        // would wrongly report the whole amount as still refundable.
        val explicitRefunded = (o.entries["refundedAmount"] as? JsonValue.JsonNumber)?.toDouble()
        intent.refundedAmount = when {
            explicitRefunded == null ->
                if (intent.status == PaymentIntent.Status.REFUNDED) intent.amount else 0.0
            explicitRefunded < 0.0 || explicitRefunded > intent.amount + 1e-9 ->
                throw InvalidSnapshotException(
                    "refundedAmount $explicitRefunded out of range for intent ${intent.id} (amount ${intent.amount})."
                )
            else -> explicitRefunded
        }
        // Validate status/refundedAmount consistency.
        val fullyRefunded = Math.abs(intent.refundedAmount - intent.amount) <= 1e-9
        when {
            intent.status == PaymentIntent.Status.REFUNDED && !fullyRefunded ->
                throw InvalidSnapshotException(
                    "Intent ${intent.id} has REFUNDED status but refundedAmount ${intent.refundedAmount} != amount ${intent.amount}."
                )
            intent.status == PaymentIntent.Status.SUCCEEDED && fullyRefunded ->
                throw InvalidSnapshotException(
                    "Intent ${intent.id} has SUCCEEDED status but is fully refunded (${intent.refundedAmount} of ${intent.amount})."
                )
        }
        return intent
    }

    private fun decodeAuditEntry(o: JsonValue.JsonObject): AuditLog.Entry = AuditLog.Entry(
        timestamp = LocalDateTime.parse(o.string("timestamp")),
        bookingId = o.string("bookingId"),
        action = AuditLog.Action.valueOf(o.string("action")),
        detail = o.string("detail")
    )
}
