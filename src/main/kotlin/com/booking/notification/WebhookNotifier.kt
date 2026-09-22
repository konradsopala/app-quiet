package com.booking.notification

import com.booking.persistence.JsonValue
import com.booking.persistence.JsonValue.Companion.obj
import com.booking.persistence.JsonValue.Companion.stringOrNull
import com.booking.persistence.JsonWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pushes every [NotificationEvent] to an HTTP endpoint as signed JSON.
 *
 * This is the one channel that leaves the machine, so it is deliberately
 * strict: the endpoint must be `https` unless it is loopback, the request
 * carries an HMAC-SHA256 signature over `"<timestamp>.<body>"` so the
 * receiver can reject forged or replayed deliveries, and a non-2xx answer
 * is an error the dispatcher will log rather than swallow silently.
 *
 * [transport] is injectable so tests can capture the request instead of
 * opening a socket.
 */
class WebhookNotifier(
    endpoint: String,
    private val secret: String,
    private val timeoutMillis: Int = 5_000,
    private val clock: () -> Instant = Instant::now,
    transport: ((URL, Map<String, String>, ByteArray) -> Int)? = null
) : Notifier {

    private val send: (URL, Map<String, String>, ByteArray) -> Int =
        transport ?: { url, headers, body -> httpPost(url, headers, body, timeoutMillis) }

    override val name: String = "webhook"

    val endpoint: URL = parseEndpoint(endpoint)

    init {
        require(secret.length >= MIN_SECRET_LENGTH) {
            "Webhook secret must be at least $MIN_SECRET_LENGTH characters; deliveries would be trivially forgeable otherwise."
        }
        require(timeoutMillis in 100..60_000) { "Webhook timeout must be between 100 ms and 60 s" }
    }

    override fun handle(event: NotificationEvent) {
        val body = JsonWriter().write(encode(event))
        val timestamp = clock().epochSecond
        val headers = mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "User-Agent" to "booking-cli-webhook/1",
            HEADER_EVENT to eventType(event),
            HEADER_TIMESTAMP to timestamp.toString(),
            HEADER_SIGNATURE to "sha256=" + sign(timestamp, body)
        )
        val status = send(endpoint, headers, body.toByteArray(Charsets.UTF_8))
        if (status !in 200..299) {
            throw IOException("Webhook endpoint ${endpoint.host} answered HTTP $status for ${eventType(event)}")
        }
    }

    // ── Payload ───────────────────────────────────────────────────────

    /** Stable wire shape; the receiver should key off `type`, never `summary`. */
    fun encode(event: NotificationEvent): JsonValue.JsonObject {
        val common = listOf(
            "type" to JsonValue.JsonString(eventType(event)),
            "occurredAt" to JsonValue.JsonString(clock().toString()),
            "customerName" to JsonValue.JsonString(event.customerName),
            "summary" to JsonValue.JsonString(event.summary)
        )
        val specific: List<Pair<String, JsonValue>> = when (event) {
            is NotificationEvent.BookingCreated -> booking(event.booking.id, event.booking.date.toString())
            is NotificationEvent.BookingCancelled -> booking(event.booking.id, event.booking.date.toString())
            is NotificationEvent.PaymentSucceeded -> payment(event.intent.id, event.booking.id, event.intent.amount, event.intent.currency)
            is NotificationEvent.PaymentFailed -> payment(event.intent.id, event.booking.id, event.intent.amount, event.intent.currency) +
                ("failureReason" to stringOrNull(event.intent.failureReason))
            is NotificationEvent.PaymentRefunded -> payment(event.intent.id, event.booking.id, event.intent.amount, event.intent.currency)
            is NotificationEvent.WaitlistPromoted -> booking(event.booking.id, event.booking.date.toString()) +
                ("waitlistEntryId" to JsonValue.JsonString(event.entry.id))
            is NotificationEvent.GiftCardActivity -> listOf(
                // The card code is a bearer credential: only the last group goes over the wire.
                "cardCodeSuffix" to JsonValue.JsonString(event.card.code.takeLast(4)),
                "cardId" to JsonValue.JsonString(event.card.id),
                "transactionType" to JsonValue.JsonString(event.transaction.type.name),
                "amount" to JsonValue.JsonNumber(event.transaction.amount),
                "balanceAfter" to JsonValue.JsonNumber(event.transaction.balanceAfter),
                "currency" to JsonValue.JsonString(event.card.currency),
                "bookingId" to stringOrNull(event.transaction.bookingId),
                "actor" to JsonValue.JsonString(event.actor)
            )
        }
        return obj(*(common + specific).toTypedArray())
    }

    private fun booking(id: String, date: String) = listOf(
        "bookingId" to JsonValue.JsonString(id) as JsonValue,
        "date" to JsonValue.JsonString(date)
    )

    private fun payment(intentId: String, bookingId: String, amount: Double, currency: String) = listOf(
        "paymentIntentId" to JsonValue.JsonString(intentId) as JsonValue,
        "bookingId" to JsonValue.JsonString(bookingId),
        "amount" to JsonValue.JsonNumber(amount),
        "currency" to JsonValue.JsonString(currency)
    )

    // ── Signing ───────────────────────────────────────────────────────

    fun sign(timestamp: Long, body: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal("$timestamp.$body".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Receiver-side check, exposed so an integration test can prove the
     * two halves agree. Rejects signatures older than [toleranceSeconds]
     * to blunt replays, and compares in constant time.
     */
    fun verify(timestamp: Long, body: String, signatureHeader: String, toleranceSeconds: Long = 300): Boolean {
        val age = clock().epochSecond - timestamp
        if (age < -toleranceSeconds || age > toleranceSeconds) return false
        val presented = signatureHeader.removePrefix("sha256=")
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            sign(timestamp, body).toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        const val HEADER_SIGNATURE = "X-Booking-Signature"
        const val HEADER_TIMESTAMP = "X-Booking-Timestamp"
        const val HEADER_EVENT = "X-Booking-Event"
        const val MIN_SECRET_LENGTH = 16
        private const val HMAC_ALGORITHM = "HmacSHA256"

        fun eventType(event: NotificationEvent): String = when (event) {
            is NotificationEvent.BookingCreated -> "booking.created"
            is NotificationEvent.BookingCancelled -> "booking.cancelled"
            is NotificationEvent.PaymentSucceeded -> "payment.succeeded"
            is NotificationEvent.PaymentFailed -> "payment.failed"
            is NotificationEvent.PaymentRefunded -> "payment.refunded"
            is NotificationEvent.WaitlistPromoted -> "waitlist.promoted"
            is NotificationEvent.GiftCardActivity -> "gift_card." + event.transaction.type.name.lowercase()
        }

        /** `https` only, except for loopback so a local receiver can be used in development. */
        fun parseEndpoint(raw: String): URL {
            val uri = try { URI(raw.trim()) } catch (e: Exception) {
                throw IllegalArgumentException("Webhook URL is not valid: $raw")
            }
            val host = uri.host ?: throw IllegalArgumentException("Webhook URL needs a host: $raw")
            val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
            require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
                "Webhook URL must use https (plain http is allowed for localhost only): $raw"
            }
            require(uri.userInfo == null) { "Webhook URL must not embed credentials" }
            return uri.toURL()
        }

        /** Default transport: a blocking POST with connect/read timeouts. */
        fun httpPost(url: URL, headers: Map<String, String>, body: ByteArray, timeout: Int): Int {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = timeout
                conn.readTimeout = timeout
                conn.doOutput = true
                conn.instanceFollowRedirects = false
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                return conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }
}
