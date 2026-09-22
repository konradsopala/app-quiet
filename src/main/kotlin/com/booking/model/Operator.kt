package com.booking.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * A till operator: someone who signs in to the CLI before touching money.
 *
 * The PIN is never stored. [pinHash] and [pinSalt] hold the output of
 * `PinHasher`, and every mutable security field has an `internal` setter so
 * only `OperatorService` (and the snapshot restore path) can move it.
 *
 * Roles are ordered by privilege; the mapping from role to concrete
 * permissions lives in `AccessPolicy`, not here, so the model stays free of
 * policy.
 */
class Operator(
    username: String,
    displayName: String,
    val role: Role,
    pinHash: String,
    pinSalt: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    id: String? = null
) {
    enum class Role { CASHIER, MANAGER, ADMIN }

    companion object {
        /** Lower-case handle typed at sign-in. Kept short so it fits the audit log. */
        val USERNAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]{2,31}")
    }

    val id: String = id ?: ("op_" + UUID.randomUUID().toString().replace("-", "").take(20))
    val username: String = username.trim().lowercase()
    val displayName: String = displayName.trim()

    var pinHash: String = pinHash
        internal set
    var pinSalt: String = pinSalt
        internal set

    /** Inactive operators keep their history but can no longer sign in. */
    var active: Boolean = true
        internal set

    /** Consecutive failed sign-ins since the last success; drives lockout. */
    var failedAttempts: Int = 0
        internal set

    /** While set and in the future, sign-in is refused without checking the PIN. */
    var lockedUntil: LocalDateTime? = null
        internal set

    var lastSignInAt: LocalDateTime? = null
        internal set

    /** Set on bootstrap accounts and after an admin PIN reset. */
    var mustChangePin: Boolean = false
        internal set

    init {
        require(USERNAME_PATTERN.matches(this.username)) {
            "Username must be 3-32 chars of a-z, 0-9, '.', '_' or '-' and start with a letter or digit."
        }
        require(this.displayName.isNotEmpty()) { "Display name cannot be blank." }
    }

    fun isLockedAt(now: LocalDateTime): Boolean = lockedUntil?.isAfter(now) == true

    /** Snapshot restore: bring every mutable field back in one call. */
    internal fun restoreState(
        active: Boolean,
        failedAttempts: Int,
        lockedUntil: LocalDateTime?,
        lastSignInAt: LocalDateTime?,
        mustChangePin: Boolean
    ) {
        this.active = active
        this.failedAttempts = failedAttempts
        this.lockedUntil = lockedUntil
        this.lastSignInAt = lastSignInAt
        this.mustChangePin = mustChangePin
    }

    override fun toString(): String {
        val state = when {
            !active -> "inactive"
            lockedUntil != null && lockedUntil!!.isAfter(LocalDateTime.now()) -> "locked until $lockedUntil"
            mustChangePin -> "must change PIN"
            else -> "active"
        }
        return "$username ($displayName, $role) — $state"
    }
}
