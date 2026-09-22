package com.booking.service

import com.booking.config.AppConfig
import com.booking.model.Booking
import com.booking.model.ConsentRecord
import com.booking.model.Customer
import com.booking.model.PrivacyRequest
import com.booking.notification.NotificationPreferences
import com.booking.persistence.JsonValue
import com.booking.persistence.JsonValue.Companion.arr
import com.booking.persistence.JsonValue.Companion.obj
import com.booking.persistence.JsonValue.Companion.stringOrNull
import com.booking.persistence.JsonWriter
import com.booking.privacy.PiiRedactor
import com.booking.privacy.RetentionPolicy
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Everything the business owes a data subject: knowing what consent it
 * holds, answering access requests with a complete export, erasing a
 * person across every service that remembers them, and not keeping data
 * longer than the retention policy allows.
 *
 * This service is the only place that reaches *into* other services to
 * rewrite their records. It does so through `internal` hooks on the
 * models rather than public setters, so ordinary code paths cannot
 * accidentally anonymise anything.
 *
 * Erasure is anonymisation, not deletion. Rows stay so referential
 * integrity, revenue totals and the audit trail survive; every
 * identifying field is replaced with a keyed pseudonym or scrubbed. The
 * pseudonym is stable, so an erased customer's history still groups
 * together in analytics, and the mapping cannot be reversed without
 * [AppConfig.pseudonymSecret].
 *
 * Invoices are the one deliberate exception: tax law requires the buyer
 * name on an issued invoice to be retained, so they are counted and
 * reported as "retained under legal obligation" instead of rewritten.
 */
class PrivacyService(
    private val service: BookingService,
    private val customers: CustomerService,
    private val payments: PaymentService,
    private val waitlist: WaitlistService,
    private val reviews: ReviewService,
    private val invoices: InvoiceService,
    private val preferences: NotificationPreferences,
    private val config: AppConfig = AppConfig.DEFAULT,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {
    class PrivacyException(message: String) : RuntimeException(message)

    val policy: RetentionPolicy = RetentionPolicy.fromConfig(config)
    private val redactor = PiiRedactor(config.pseudonymSecret)

    private val consents = mutableListOf<ConsentRecord>()
    private val requests = linkedMapOf<String, PrivacyRequest>()
    private val erasures = linkedMapOf<String, ErasureRecord>()

    /** A resolved data subject: the directory record when there is one, plus every name we know them by. */
    data class Subject(val customer: Customer?, val displayName: String, val names: Set<String>) {
        val key: String get() = customer?.id ?: displayName.trim().lowercase()
    }

    /** Proof of one erasure. Holds the pseudonym and counts only — never the identity it replaced. */
    data class ErasureRecord(
        val pseudonym: String,
        val requestId: String,
        val erasedAt: LocalDateTime,
        val bookingsAnonymised: Int,
        val reviewsAnonymised: Int,
        val waitlistEntriesAnonymised: Int,
        val paymentReasonsCleared: Int,
        val auditEntriesRedacted: Int,
        val invoicesRetained: Int
    ) {
        override fun toString(): String =
            "$pseudonym erased ${erasedAt.toLocalDate()} (request $requestId): $bookingsAnonymised booking(s), " +
                "$reviewsAnonymised review(s), $waitlistEntriesAnonymised waitlist, $paymentReasonsCleared payment reason(s), " +
                "$auditEntriesRedacted audit entries; $invoicesRetained invoice(s) retained"
    }

    data class ExportResult(val path: String, val sha256: String, val sectionCounts: Map<String, Int>)

    // ── Subject resolution ────────────────────────────────────────────

    /**
     * Find who [ref] refers to: a customer id, an exact name, or an email.
     * Falls back to a "name-only" subject when bookings exist under that
     * name without a directory record, because those bookings are still
     * personal data we must be able to export and erase.
     */
    fun resolveSubject(ref: String): Subject? {
        val needle = ref.trim()
        if (needle.isEmpty()) return null
        val customer = customers.find(needle)
            ?: customers.findByExactName(needle)
            ?: (if (needle.contains('@')) customers.findByEmail(needle) else null)
        if (customer != null) {
            return Subject(customer, customer.name, setOf(customer.name))
        }
        val nameOnly = service.listBookings().filter { it.customerName.equals(needle, ignoreCase = true) }
        if (nameOnly.isEmpty()) return null
        return Subject(null, nameOnly.first().customerName, nameOnly.map { it.customerName }.toSet())
    }

    private fun requireSubject(ref: String): Subject =
        resolveSubject(ref) ?: throw PrivacyException("No customer or booking matches '$ref'.")

    // ── Consent ───────────────────────────────────────────────────────

    fun recordConsent(
        subjectRef: String,
        purpose: ConsentRecord.Purpose,
        granted: Boolean,
        source: String,
        expiresAt: LocalDate? = null
    ): ConsentRecord {
        val subject = requireSubject(subjectRef)
        if (!purpose.requiresConsent && !granted) {
            throw PrivacyException(
                "${purpose.name} is processed under '${purpose.lawfulBasis}', not consent; it cannot be declined here."
            )
        }
        // A new decision supersedes any standing grant for the same purpose.
        effectiveConsent(subject, purpose)?.withdraw(clock())
        val record = ConsentRecord(subject.key, purpose, granted, source, recordedAt = clock(), expiresAt = expiresAt)
        consents.add(record)
        service.auditLog.log(
            subject.key, if (granted) AuditLog.Action.CONSENT_RECORDED else AuditLog.Action.CONSENT_WITHDRAWN,
            "${purpose.name} ${if (granted) "granted" else "declined"} via ${record.source}" +
                (expiresAt?.let { ", expires $it" } ?: "")
        )
        return record
    }

    fun withdrawConsent(subjectRef: String, purpose: ConsentRecord.Purpose): ConsentRecord {
        val subject = requireSubject(subjectRef)
        val record = effectiveConsent(subject, purpose)
            ?: throw PrivacyException("${subject.displayName} has no standing ${purpose.name} consent to withdraw.")
        record.withdraw(clock())
        service.auditLog.log(subject.key, AuditLog.Action.CONSENT_WITHDRAWN, "${purpose.name} withdrawn")
        return record
    }

    /** Purposes with a non-consent lawful basis are always allowed; the rest need a standing grant. */
    fun hasConsent(subjectRef: String, purpose: ConsentRecord.Purpose, asOf: LocalDate = clock().toLocalDate()): Boolean {
        if (!purpose.requiresConsent) return true
        val subject = resolveSubject(subjectRef) ?: return false
        return effectiveConsent(subject, purpose, asOf) != null
    }

    fun consentsFor(subjectRef: String): List<ConsentRecord> {
        val subject = resolveSubject(subjectRef) ?: return emptyList()
        return consents.filter { it.subjectId == subject.key }.sortedBy { it.recordedAt }
    }

    fun allConsents(): List<ConsentRecord> = consents.toList()

    private fun effectiveConsent(subject: Subject, purpose: ConsentRecord.Purpose, asOf: LocalDate = clock().toLocalDate()): ConsentRecord? =
        consents.lastOrNull { it.subjectId == subject.key && it.purpose == purpose && it.isEffectiveOn(asOf) }

    // ── Requests ──────────────────────────────────────────────────────

    fun openRequest(type: PrivacyRequest.Type, subjectRef: String, channel: String): PrivacyRequest {
        val now = clock()
        val subject = resolveSubject(subjectRef)
        val request = PrivacyRequest(
            type = type,
            subjectRef = subjectRef,
            subjectId = subject?.key,
            channel = channel,
            receivedAt = now,
            dueAt = now.toLocalDate().plusDays(config.privacyRequestSlaDays)
        )
        requests[request.id] = request
        service.auditLog.log(
            subject?.key ?: "UNMATCHED", AuditLog.Action.PRIVACY_REQUEST_OPENED,
            "${request.id}: $type via ${request.channel}, due ${request.dueAt}" +
                (if (subject == null) " (subject not matched)" else "")
        )
        return request
    }

    fun verifyRequest(id: String, method: String): PrivacyRequest = transition(id) { it.verify(method, clock()); "verified by $method" }

    fun startRequest(id: String): PrivacyRequest = transition(id) { it.start(); "in progress" }

    fun rejectRequest(id: String, reason: String): PrivacyRequest = transition(id) { it.reject(reason, clock()); "rejected: $reason" }

    fun completeRequest(id: String, outcome: String): PrivacyRequest = transition(id) { it.complete(outcome, clock()); "completed: $outcome" }

    fun addRequestNote(id: String, note: String): PrivacyRequest {
        val request = requireRequest(id)
        request.addNote(note)
        return request
    }

    fun findRequest(id: String): PrivacyRequest? = requests[id.trim()]

    fun listRequests(openOnly: Boolean = false): List<PrivacyRequest> =
        requests.values.filter { !openOnly || it.isOpen() }.sortedBy { it.dueAt }

    fun overdueRequests(asOf: LocalDate = clock().toLocalDate()): List<PrivacyRequest> =
        requests.values.filter { it.isOverdue(asOf) }.sortedBy { it.dueAt }

    private fun transition(id: String, action: (PrivacyRequest) -> String): PrivacyRequest {
        val request = requireRequest(id)
        val detail = try { action(request) } catch (e: IllegalStateException) {
            throw PrivacyException(e.message ?: "Illegal transition")
        } catch (e: IllegalArgumentException) {
            throw PrivacyException(e.message ?: "Invalid input")
        }
        service.auditLog.log(request.subjectId ?: "UNMATCHED", AuditLog.Action.PRIVACY_REQUEST_UPDATED, "${request.id}: $detail")
        return request
    }

    private fun requireRequest(id: String): PrivacyRequest =
        requests[id.trim()] ?: throw PrivacyException("No privacy request with id '${id.trim()}'.")

    private fun requireActionable(requestId: String, type: PrivacyRequest.Type, subject: Subject): PrivacyRequest {
        val request = requireRequest(requestId)
        if (request.type != type) throw PrivacyException("Request ${request.id} is a ${request.type} request, not $type.")
        if (request.status != PrivacyRequest.Status.VERIFIED && request.status != PrivacyRequest.Status.IN_PROGRESS) {
            throw PrivacyException("Request ${request.id} is ${request.status}; identity must be verified before acting on it.")
        }
        if (request.subjectId != null && request.subjectId != subject.key) {
            throw PrivacyException("Request ${request.id} was matched to ${request.subjectId}, not ${subject.key}.")
        }
        return request
    }

    // ── Access requests: subject data export ──────────────────────────

    /**
     * Write everything held about the subject to one JSON file plus a
     * `.sha256` sidecar so the recipient can prove the copy is intact.
     * The export is unmasked — it is *for* the subject — which is why it
     * demands a verified ACCESS request.
     */
    fun exportSubjectData(subjectRef: String, requestId: String, directory: String = config.defaultSubjectExportDir): ExportResult {
        val subject = requireSubject(subjectRef)
        val request = requireActionable(requestId, PrivacyRequest.Type.ACCESS, subject)
        if (request.status == PrivacyRequest.Status.VERIFIED) request.start()

        val bookings = bookingsOf(subject)
        val bookingIds = bookings.map { it.id }.toSet()
        val intents = payments.listForBookings(bookingIds)
        val subjectInvoices = invoices.list().filter { it.bookingId in bookingIds }
        val subjectReviews = reviews.reviewsForSubject(subject.customer?.id, subject.displayName)
        val subjectWaitlist = waitlist.list().filter { e -> subject.names.any { it.equals(e.customerName, ignoreCase = true) } }
        val subjectConsents = consents.filter { it.subjectId == subject.key }
        val audit = service.auditLog.getAll().filter { e ->
            e.bookingId in bookingIds || e.bookingId == subject.key || subject.names.any { e.detail.contains(it, ignoreCase = true) }
        }

        val tree = obj(
            "exportedAt" to JsonValue.JsonString(clock().toString()),
            "requestId" to JsonValue.JsonString(request.id),
            "subject" to obj(
                "displayName" to JsonValue.JsonString(subject.displayName),
                "customerId" to stringOrNull(subject.customer?.id),
                "email" to stringOrNull(subject.customer?.email),
                "phone" to stringOrNull(subject.customer?.phone),
                "loyaltyYears" to JsonValue.JsonNumber(subject.customer?.loyaltyYears ?: 0),
                "notes" to stringOrNull(subject.customer?.notes),
                "createdAt" to stringOrNull(subject.customer?.createdAt?.toString())
            ),
            "bookings" to arr(bookings.map { b ->
                obj(
                    "id" to JsonValue.JsonString(b.id), "date" to JsonValue.JsonString(b.date.toString()),
                    "start" to JsonValue.JsonString(b.startTime.toString()), "durationMinutes" to JsonValue.JsonNumber(b.durationMinutes),
                    "description" to JsonValue.JsonString(b.description), "status" to JsonValue.JsonString(b.status.name),
                    "notes" to stringOrNull(b.notes), "tags" to arr(b.tags.sorted().map { JsonValue.JsonString(it) }),
                    "quotedTotal" to (b.quote?.let { JsonValue.JsonNumber(it.total) } ?: JsonValue.JsonNull)
                )
            }),
            "payments" to arr(intents.map { p ->
                obj(
                    "id" to JsonValue.JsonString(p.id), "bookingId" to JsonValue.JsonString(p.bookingId),
                    "amount" to JsonValue.JsonNumber(p.amount), "currency" to JsonValue.JsonString(p.currency),
                    "status" to JsonValue.JsonString(p.status.name), "refundedAmount" to JsonValue.JsonNumber(p.refundedAmount),
                    "createdAt" to JsonValue.JsonString(p.createdAt.toString())
                )
            }),
            "invoices" to arr(subjectInvoices.map { i ->
                obj(
                    "id" to JsonValue.JsonString(i.id), "number" to stringOrNull(i.invoiceNumber),
                    "status" to JsonValue.JsonString(i.status.name), "total" to JsonValue.JsonNumber(i.total),
                    "currency" to JsonValue.JsonString(i.currency), "issueDate" to stringOrNull(i.issueDate?.toString())
                )
            }),
            "reviews" to arr(subjectReviews.map { r ->
                obj(
                    "id" to JsonValue.JsonString(r.id), "bookingId" to JsonValue.JsonString(r.bookingId),
                    "rating" to JsonValue.JsonNumber(r.rating), "comment" to stringOrNull(r.comment),
                    "createdAt" to JsonValue.JsonString(r.createdAt.toString())
                )
            }),
            "waitlist" to arr(subjectWaitlist.map { w ->
                obj(
                    "id" to JsonValue.JsonString(w.id), "date" to JsonValue.JsonString(w.date.toString()),
                    "start" to JsonValue.JsonString(w.startTime.toString()), "description" to JsonValue.JsonString(w.description),
                    "priority" to JsonValue.JsonString(w.priority.name), "addedAt" to JsonValue.JsonString(w.addedAt.toString())
                )
            }),
            "consents" to arr(subjectConsents.map { c ->
                obj(
                    "purpose" to JsonValue.JsonString(c.purpose.name), "granted" to JsonValue.JsonBoolean(c.granted),
                    "source" to JsonValue.JsonString(c.source), "recordedAt" to JsonValue.JsonString(c.recordedAt.toString()),
                    "withdrawnAt" to stringOrNull(c.withdrawnAt?.toString()), "expiresAt" to stringOrNull(c.expiresAt?.toString())
                )
            }),
            "notificationPreferences" to obj(
                "mutedChannels" to arr(preferences.mutedChannels(subject.displayName).sorted().map { JsonValue.JsonString(it) }),
                "mutedEvents" to arr(preferences.mutedEvents(subject.displayName).map { JsonValue.JsonString("${it.first}:${it.second}") })
            ),
            "auditTrail" to arr(audit.map { e ->
                obj(
                    "timestamp" to JsonValue.JsonString(e.timestamp.toString()), "action" to JsonValue.JsonString(e.action.name),
                    "detail" to JsonValue.JsonString(e.detail)
                )
            })
        )

        val dir = File(directory).apply { mkdirs() }
        val safeName = subject.key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "subject-$safeName-${clock().toLocalDate()}.json")
        val json = JsonWriter().write(tree)
        file.writeText(json)
        val checksum = sha256Hex(json)
        File(dir, file.name + ".sha256").writeText("$checksum  ${file.name}\n")

        val counts = mapOf(
            "bookings" to bookings.size, "payments" to intents.size, "invoices" to subjectInvoices.size,
            "reviews" to subjectReviews.size, "waitlist" to subjectWaitlist.size, "consents" to subjectConsents.size,
            "auditTrail" to audit.size
        )
        service.auditLog.log(
            subject.key, AuditLog.Action.SUBJECT_DATA_EXPORTED,
            "Request ${request.id}: exported ${counts.values.sum()} record(s) to ${file.path} sha256=$checksum"
        )
        return ExportResult(file.path, checksum, counts)
    }

    // ── Erasure requests ──────────────────────────────────────────────

    /**
     * Anonymise the subject everywhere. Refused while they still have a
     * confirmed booking in the future — the contract is still running and
     * the till needs to know who is arriving. Cancel those first.
     */
    fun eraseSubject(subjectRef: String, requestId: String): ErasureRecord {
        val subject = requireSubject(subjectRef)
        val request = requireActionable(requestId, PrivacyRequest.Type.ERASURE, subject)
        val today = clock().toLocalDate()
        val bookings = bookingsOf(subject)
        val upcoming = bookings.filter { it.status == Booking.Status.CONFIRMED && !it.date.isBefore(today) }
        if (upcoming.isNotEmpty()) {
            throw PrivacyException(
                "${subject.displayName} has ${upcoming.size} upcoming confirmed booking(s) (next ${upcoming.minOf { it.date }}); cancel them before erasing."
            )
        }
        if (subject.customer?.isErased == true) {
            throw PrivacyException("${subject.displayName} was already erased.")
        }
        if (request.status == PrivacyRequest.Status.VERIFIED) request.start()

        val now = clock()
        val pseudonym = redactor.pseudonym(subject.key)
        val names = subject.names + listOfNotNull(subject.customer?.name)
        val contacts = listOfNotNull(subject.customer?.email, subject.customer?.phone)
        val scrubTerms = names + contacts

        // Bookings: name → pseudonym, free text scrubbed, upstream reference dropped.
        for (b in bookings) {
            b.anonymize(
                pseudonym,
                scrubbedDescription = redactor.scrubOrEmpty(b.description, scrubTerms),
                scrubbedNotes = redactor.scrub(b.notes, scrubTerms)
            )
        }
        val bookingIds = bookings.map { it.id }.toSet()

        // Reviews keep their rating (aggregate feedback is not personal data) but lose the author.
        val subjectReviews = reviews.reviewsForSubject(subject.customer?.id, subject.displayName)
        subjectReviews.forEach { it.anonymize(pseudonym, redactor.scrub(it.comment, scrubTerms)) }

        val waitlistCount = names.sumOf { waitlist.anonymizeCustomer(it, pseudonym) }

        // Processor failure strings can quote card fragments or the cardholder name.
        var paymentReasons = 0
        for (intent in payments.listForBookings(bookingIds)) {
            if (intent.failureReason != null) { intent.failureReason = redactor.scrub(intent.failureReason, scrubTerms); paymentReasons++ }
        }

        val invoicesRetained = invoices.list().count { it.bookingId in bookingIds }

        names.forEach { preferences.clear(it) }

        consents.filter { it.subjectId == subject.key }.forEach { it.subjectId = pseudonym }
        requests.values.filter { it.subjectId == subject.key }.forEach { r ->
            r.subjectId = pseudonym
            r.subjectRef = pseudonym
        }

        // Audit: keep every entry, but nothing in it may still name the person.
        val auditRedacted = service.auditLog.rewrite { e ->
            val detail = redactor.scrubOrEmpty(e.detail, scrubTerms)
            val bookingId = if (e.bookingId == subject.key) pseudonym else e.bookingId
            if (detail == e.detail && bookingId == e.bookingId) null else e.copy(bookingId = bookingId, detail = detail)
        }

        subject.customer?.let { customers.anonymize(it.id, pseudonym, now) }

        val record = ErasureRecord(
            pseudonym = pseudonym, requestId = request.id, erasedAt = now,
            bookingsAnonymised = bookings.size, reviewsAnonymised = subjectReviews.size,
            waitlistEntriesAnonymised = waitlistCount, paymentReasonsCleared = paymentReasons,
            auditEntriesRedacted = auditRedacted, invoicesRetained = invoicesRetained
        )
        erasures[pseudonym] = record
        request.complete("erased as $pseudonym; $invoicesRetained invoice(s) retained under legal obligation", now)
        service.auditLog.log(pseudonym, AuditLog.Action.SUBJECT_ERASED, record.toString())
        return record
    }

    fun erasureRegister(): List<ErasureRecord> = erasures.values.toList()

    // ── Retention ─────────────────────────────────────────────────────

    /** Apply [policy] to everything that has aged past its window. With [dryRun] nothing is changed, only counted. */
    fun applyRetention(asOf: LocalDate = clock().toLocalDate(), dryRun: Boolean = false): RetentionPolicy.Report {
        var bookingsMinimised = 0
        val bookingCutoff = policy.cancelledBookingCutoff(asOf)
        for (b in service.listBookings()) {
            if (b.status != Booking.Status.CANCELLED || !b.date.isBefore(bookingCutoff)) continue
            if (redactor.isPseudonym(b.customerName)) continue
            bookingsMinimised++
            if (!dryRun) {
                val pseudonym = redactor.pseudonym(b.customerId ?: b.customerName.trim().lowercase())
                b.anonymize(pseudonym, redactor.scrubOrEmpty(b.description, listOf(b.customerName)), redactor.scrub(b.notes, listOf(b.customerName)))
            }
        }

        var paymentReasons = 0
        val paymentCutoff = policy.paymentFailureCutoff(asOf)
        for (p in payments.list()) {
            if (p.failureReason == null || !p.createdAt.toLocalDate().isBefore(paymentCutoff)) continue
            paymentReasons++
            if (!dryRun) p.failureReason = null
        }

        val stale = waitlist.list().filter { it.date.isBefore(policy.staleWaitlistCutoff(asOf)) }
        if (!dryRun) stale.forEach { waitlist.remove(it.id) }

        var auditScrubbed = 0
        val auditCutoff = policy.auditDetailCutoff(asOf)
        if (dryRun) {
            auditScrubbed = service.auditLog.getAll().count { e ->
                e.timestamp.toLocalDate().isBefore(auditCutoff) && redactor.scrubOrEmpty(e.detail) != e.detail
            }
        } else {
            auditScrubbed = service.auditLog.rewrite { e ->
                if (!e.timestamp.toLocalDate().isBefore(auditCutoff)) return@rewrite null
                val detail = redactor.scrubOrEmpty(e.detail)
                if (detail == e.detail) null else e.copy(detail = detail)
            }
        }

        val inactiveCutoff = policy.inactiveCustomerCutoff(asOf)
        val lastBookingByCustomer = service.listBookings().groupBy { it.customerId }.mapValues { (_, bs) -> bs.maxOf { it.date } }
        val candidates = customers.list().filter { c ->
            !c.isErased && c.createdAt.toLocalDate().isBefore(inactiveCutoff) &&
                (lastBookingByCustomer[c.id]?.isBefore(inactiveCutoff) ?: true)
        }.map { "${it.id} (${it.name})" }

        val report = RetentionPolicy.Report(
            asOf = asOf, bookingsMinimised = bookingsMinimised, paymentReasonsCleared = paymentReasons,
            waitlistEntriesDropped = stale.size, auditEntriesScrubbed = auditScrubbed,
            inactiveCustomerCandidates = candidates, dryRun = dryRun
        )
        if (!dryRun) {
            service.auditLog.log(
                "SYSTEM", AuditLog.Action.RETENTION_APPLIED,
                "As of $asOf: ${report.bookingsMinimised} booking(s) minimised, ${report.paymentReasonsCleared} payment reason(s) cleared, " +
                    "${report.waitlistEntriesDropped} waitlist dropped, ${report.auditEntriesScrubbed} audit entries scrubbed"
            )
        }
        return report
    }

    // ── Dashboard ─────────────────────────────────────────────────────

    data class Dashboard(
        val asOf: LocalDate,
        val openRequests: Int,
        val overdueRequests: Int,
        val requestsByType: Map<PrivacyRequest.Type, Int>,
        val consentsByPurpose: Map<ConsentRecord.Purpose, Pair<Int, Int>>,
        val erasedSubjects: Int,
        val erasedCustomers: Int,
        val customersTotal: Int
    )

    fun dashboard(asOf: LocalDate = clock().toLocalDate()): Dashboard {
        val open = requests.values.filter { it.isOpen() }
        val byPurpose = ConsentRecord.Purpose.values().associateWith { p ->
            val active = consents.count { it.purpose == p && it.isEffectiveOn(asOf) }
            val withdrawnOrDeclined = consents.count { it.purpose == p && !it.isEffectiveOn(asOf) }
            active to withdrawnOrDeclined
        }
        return Dashboard(
            asOf = asOf,
            openRequests = open.size,
            overdueRequests = open.count { it.isOverdue(asOf) },
            requestsByType = PrivacyRequest.Type.values().associateWith { t -> requests.values.count { it.type == t } },
            consentsByPurpose = byPurpose,
            erasedSubjects = erasures.size,
            erasedCustomers = customers.list().count { it.isErased },
            customersTotal = customers.size()
        )
    }

    // ── Snapshot support ──────────────────────────────────────────────

    fun allRequests(): List<PrivacyRequest> = requests.values.toList()

    internal fun replaceAll(newConsents: List<ConsentRecord>, newRequests: List<PrivacyRequest>, newErasures: List<ErasureRecord>) {
        consents.clear(); consents.addAll(newConsents)
        requests.clear(); newRequests.forEach { requests[it.id] = it }
        erasures.clear(); newErasures.forEach { erasures[it.pseudonym] = it }
    }

    // ── Internals ─────────────────────────────────────────────────────

    private fun bookingsOf(subject: Subject): List<Booking> {
        val byId = subject.customer?.let { service.findByCustomerId(it.id) } ?: emptyList()
        val byName = service.listBookings().filter { b -> subject.names.any { it.equals(b.customerName, ignoreCase = true) } }
        return (byId + byName).distinctBy { it.id }.sortedBy { it.date }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
