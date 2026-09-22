package com.booking.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A data-subject request: someone asking what we hold about them, to
 * have it corrected, restricted, or erased.
 *
 * The lifecycle is deliberately linear. A request cannot be completed
 * without first being verified — acting on an unverified erasure request
 * is how an attacker deletes someone else's history — and completed or
 * rejected requests are frozen. Every transition is timestamped so the
 * statutory clock ([dueAt]) can be audited afterwards.
 */
class PrivacyRequest(
    val type: Type,
    /** Whatever the requester gave us to identify themselves: name, email, customer id. */
    subjectRef: String,
    /** The customer record the request was matched to, when one exists. */
    subjectId: String?,
    channel: String,
    val receivedAt: LocalDateTime = LocalDateTime.now(),
    val dueAt: LocalDate,
    id: String? = null
) {
    enum class Type(val description: String) {
        ACCESS("Copy of all personal data held"),
        RECTIFICATION("Correct inaccurate personal data"),
        RESTRICTION("Stop processing but keep the data"),
        ERASURE("Delete or anonymise all personal data")
    }

    enum class Status(val terminal: Boolean) {
        RECEIVED(false), VERIFIED(false), IN_PROGRESS(false), COMPLETED(true), REJECTED(true)
    }

    companion object {
        /** Legal transitions; anything not listed is refused. */
        val ALLOWED: Map<Status, Set<Status>> = mapOf(
            Status.RECEIVED to setOf(Status.VERIFIED, Status.REJECTED),
            Status.VERIFIED to setOf(Status.IN_PROGRESS, Status.COMPLETED, Status.REJECTED),
            Status.IN_PROGRESS to setOf(Status.COMPLETED, Status.REJECTED),
            Status.COMPLETED to emptySet(),
            Status.REJECTED to emptySet()
        )
    }

    val id: String = id ?: ("dsr_" + UUID.randomUUID().toString().replace("-", "").take(12))
    var subjectRef: String = subjectRef.trim()
        internal set
    val channel: String = channel.trim()

    var subjectId: String? = subjectId
        internal set

    var status: Status = Status.RECEIVED
        private set

    /** How identity was confirmed before we acted: "photo id", "email loop", "in person". */
    var verificationMethod: String? = null
        private set
    var verifiedAt: LocalDateTime? = null
        private set
    var closedAt: LocalDateTime? = null
        private set
    /** Free-text result written at completion or rejection, e.g. the export path or the refusal ground. */
    var outcome: String? = null
        private set

    private val _notes = mutableListOf<String>()
    val notes: List<String> get() = _notes.toList()

    init {
        require(this.subjectRef.isNotEmpty()) { "subjectRef cannot be blank" }
        require(this.channel.isNotEmpty()) { "channel cannot be blank" }
        require(!dueAt.isBefore(receivedAt.toLocalDate())) { "dueAt cannot be before receivedAt" }
    }

    fun isOpen(): Boolean = !status.terminal

    fun isOverdue(asOf: LocalDate): Boolean = isOpen() && asOf.isAfter(dueAt)

    /** Days until the deadline; negative once overdue. */
    fun daysRemaining(asOf: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(asOf, dueAt)

    fun addNote(note: String) {
        val trimmed = note.trim()
        if (trimmed.isNotEmpty()) _notes.add(trimmed)
    }

    internal fun verify(method: String, at: LocalDateTime) {
        require(method.isNotBlank()) { "verification method is required" }
        move(Status.VERIFIED)
        verificationMethod = method.trim()
        verifiedAt = at
    }

    internal fun start() = move(Status.IN_PROGRESS)

    internal fun complete(outcome: String, at: LocalDateTime) {
        move(Status.COMPLETED)
        this.outcome = outcome.trim()
        closedAt = at
    }

    internal fun reject(reason: String, at: LocalDateTime) {
        require(reason.isNotBlank()) { "a rejection reason is required" }
        move(Status.REJECTED)
        outcome = reason.trim()
        closedAt = at
    }

    private fun move(to: Status) {
        val allowed = ALLOWED.getValue(status)
        if (to !in allowed) {
            throw IllegalStateException("Request $id cannot go from $status to $to")
        }
        status = to
    }

    /** Snapshot restore. */
    internal fun restoreState(
        status: Status,
        verificationMethod: String?,
        verifiedAt: LocalDateTime?,
        closedAt: LocalDateTime?,
        outcome: String?,
        notes: List<String>
    ) {
        this.status = status
        this.verificationMethod = verificationMethod
        this.verifiedAt = verifiedAt
        this.closedAt = closedAt
        this.outcome = outcome
        _notes.clear(); _notes.addAll(notes)
    }

    override fun toString(): String {
        val due = if (isOpen()) " due $dueAt" else ""
        val subj = subjectId?.let { " → $it" } ?: " (unmatched)"
        return "[$id] $type for '$subjectRef'$subj via $channel — $status$due"
    }
}
