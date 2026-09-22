package com.booking.service

import com.booking.config.AppConfig
import com.booking.model.Operator
import com.booking.security.AccessPolicy
import com.booking.security.Permission
import com.booking.security.PinHasher
import java.time.LocalDateTime

/**
 * Who is at the till, and what are they allowed to do.
 *
 * One session at a time (it is a single-terminal CLI). Everything that
 * changes an operator record or a session is audit-logged, including the
 * failures: a burst of `OPERATOR_SIGN_IN_FAILED` entries is exactly what a
 * manager wants to see when a card goes missing.
 *
 * Rules enforced here:
 *
 *   * Sign-in failures are counted per account; at [AppConfig.maxFailedSignIns]
 *     the account locks for [AppConfig.signInLockoutMinutes]. Locked and
 *     inactive accounts are refused before the PIN is checked.
 *   * Unknown usernames and wrong PINs produce the same message, and an
 *     unknown username still pays for one hash so timing does not reveal
 *     which usernames exist.
 *   * An operator flagged [Operator.mustChangePin] holds no permissions
 *     until they change it — bootstrap and reset PINs are one-shot.
 *   * The first account ever created is the bootstrap ADMIN from config;
 *     after that only `OPERATOR_MANAGE` holders can register operators.
 */
class OperatorService(
    private val service: BookingService,
    private val config: AppConfig = AppConfig.DEFAULT,
    private val hasher: PinHasher = PinHasher(),
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {
    class OperatorException(message: String) : RuntimeException(message)
    class AuthenticationException(message: String) : RuntimeException(message)
    class AccessDeniedException(message: String) : RuntimeException(message)

    data class Session(val operator: Operator, val startedAt: LocalDateTime)

    private val operatorsById = linkedMapOf<String, Operator>()
    private val operatorIdByUsername = hashMapOf<String, String>()
    private var session: Session? = null

    /** Salt/hash pair burned once so unknown-user sign-ins cost the same as real ones. */
    private val decoySalt = hasher.newSalt()
    private val decoyHash = hasher.hash("00000000", decoySalt)

    // ── Session ───────────────────────────────────────────────────────

    val current: Operator? get() = session?.operator

    fun isSignedIn(): Boolean = session != null

    /** Label for audit trails and notifications: `op:<username>` or `SYSTEM`. */
    fun actorLabel(): String = current?.let { "op:${it.username}" } ?: "SYSTEM"

    fun signIn(username: String, pin: String): Operator {
        val handle = username.trim().lowercase()
        val op = find(handle)
        if (op == null) {
            hasher.verify(pin, decoySalt, decoyHash)
            service.auditLog.log("SYSTEM", AuditLog.Action.OPERATOR_SIGN_IN_FAILED, "Unknown operator '$handle'")
            throw AuthenticationException(GENERIC_FAILURE)
        }
        val now = clock()
        if (!op.active) {
            service.auditLog.log(op.id, AuditLog.Action.OPERATOR_SIGN_IN_FAILED, "${op.username} is deactivated")
            throw AuthenticationException(GENERIC_FAILURE)
        }
        if (op.isLockedAt(now)) {
            service.auditLog.log(op.id, AuditLog.Action.OPERATOR_SIGN_IN_FAILED, "${op.username} is locked until ${op.lockedUntil}")
            throw AuthenticationException("Account is locked. Try again after ${op.lockedUntil} or ask an admin to unlock it.")
        }
        if (!hasher.verify(pin, op.pinSalt, op.pinHash)) {
            op.failedAttempts += 1
            val remaining = config.maxFailedSignIns - op.failedAttempts
            service.auditLog.log(
                op.id, AuditLog.Action.OPERATOR_SIGN_IN_FAILED,
                "Wrong PIN for ${op.username} (attempt ${op.failedAttempts} of ${config.maxFailedSignIns})"
            )
            if (remaining <= 0) {
                op.lockedUntil = now.plusMinutes(config.signInLockoutMinutes)
                op.failedAttempts = 0
                service.auditLog.log(op.id, AuditLog.Action.OPERATOR_LOCKED, "${op.username} locked until ${op.lockedUntil}")
                throw AuthenticationException("Too many failed attempts. Account locked for ${config.signInLockoutMinutes} minutes.")
            }
            throw AuthenticationException(GENERIC_FAILURE)
        }
        op.failedAttempts = 0
        op.lockedUntil = null
        op.lastSignInAt = now
        session?.let { signOut() }
        session = Session(op, now)
        service.auditLog.log(op.id, AuditLog.Action.OPERATOR_SIGNED_IN, "${op.username} signed in as ${op.role}")
        return op
    }

    fun signOut() {
        val s = session ?: return
        session = null
        service.auditLog.log(s.operator.id, AuditLog.Action.OPERATOR_SIGNED_OUT, "${s.operator.username} signed out")
    }

    // ── Authorisation ─────────────────────────────────────────────────

    fun has(permission: Permission): Boolean {
        val op = current ?: return false
        if (op.mustChangePin) return false
        return AccessPolicy.allows(op.role, permission)
    }

    /**
     * The signed-in operator, provided they hold [permission]. Every refusal
     * is logged with the permission asked for, so denied attempts are
     * visible in the audit trail.
     */
    fun require(permission: Permission): Operator {
        val op = current
        if (op == null) {
            service.auditLog.log("SYSTEM", AuditLog.Action.ACCESS_DENIED, "$permission attempted with nobody signed in")
            throw AccessDeniedException("Sign in first (option 47). $permission needs at least ${AccessPolicy.minimumRoleFor(permission)}.")
        }
        if (op.mustChangePin) {
            service.auditLog.log(op.id, AuditLog.Action.ACCESS_DENIED, "${op.username} attempted $permission before changing a temporary PIN")
            throw AccessDeniedException("Change your temporary PIN before doing anything else (option 50).")
        }
        if (!AccessPolicy.allows(op.role, permission)) {
            service.auditLog.log(op.id, AuditLog.Action.ACCESS_DENIED, "${op.username} (${op.role}) attempted $permission")
            throw AccessDeniedException("${op.username} is ${op.role}; $permission needs ${AccessPolicy.minimumRoleFor(permission)}.")
        }
        return op
    }

    /**
     * A second operator vouches for one action without taking over the
     * session — the manager leans over and types their PIN. Failed PINs
     * count toward the approver's lockout just like a sign-in.
     */
    fun authorizeOverride(permission: Permission, username: String, pin: String, reason: String): Operator {
        val requester = current ?: throw AccessDeniedException("Sign in first (option 47).")
        val handle = username.trim().lowercase()
        val approver = find(handle)
        if (approver == null || !approver.active || approver.isLockedAt(clock()) || approver.mustChangePin) {
            hasher.verify(pin, decoySalt, decoyHash)
            service.auditLog.log(requester.id, AuditLog.Action.ACCESS_DENIED, "Override for $permission refused: approver '$handle' unavailable")
            throw AuthenticationException("That operator cannot approve right now.")
        }
        if (approver.id == requester.id) {
            throw AccessDeniedException("You cannot approve your own override.")
        }
        if (!hasher.verify(pin, approver.pinSalt, approver.pinHash)) {
            approver.failedAttempts += 1
            service.auditLog.log(approver.id, AuditLog.Action.OPERATOR_SIGN_IN_FAILED, "Wrong PIN for ${approver.username} during override")
            if (approver.failedAttempts >= config.maxFailedSignIns) {
                approver.lockedUntil = clock().plusMinutes(config.signInLockoutMinutes)
                approver.failedAttempts = 0
                service.auditLog.log(approver.id, AuditLog.Action.OPERATOR_LOCKED, "${approver.username} locked until ${approver.lockedUntil}")
            }
            throw AuthenticationException(GENERIC_FAILURE)
        }
        if (!AccessPolicy.allows(approver.role, permission)) {
            service.auditLog.log(approver.id, AuditLog.Action.ACCESS_DENIED, "${approver.username} (${approver.role}) cannot approve $permission")
            throw AccessDeniedException("${approver.username} is ${approver.role}; approving $permission needs ${AccessPolicy.minimumRoleFor(permission)}.")
        }
        approver.failedAttempts = 0
        service.auditLog.log(
            requester.id, AuditLog.Action.OPERATOR_OVERRIDE,
            "${approver.username} approved $permission for ${requester.username}: $reason"
        )
        return approver
    }

    // ── Account management ────────────────────────────────────────────

    /**
     * Create the first ADMIN from config when the directory is empty.
     * Returns it (so the CLI can say so) or null if operators already exist.
     */
    fun ensureBootstrapAdmin(): Operator? {
        if (operatorsById.isNotEmpty()) return null
        val op = create(config.bootstrapAdminUsername, "Bootstrap administrator", Operator.Role.ADMIN, config.bootstrapAdminPin)
        op.mustChangePin = true
        service.auditLog.log(op.id, AuditLog.Action.OPERATOR_REGISTERED, "Bootstrap admin '${op.username}' created from config; PIN change required")
        return op
    }

    fun register(username: String, displayName: String, role: Operator.Role, pin: String): Operator {
        val actor = require(Permission.OPERATOR_MANAGE)
        val op = create(username, displayName, role, pin)
        service.auditLog.log(actor.id, AuditLog.Action.OPERATOR_REGISTERED, "${actor.username} registered ${op.username} as $role")
        return op
    }

    fun changePin(currentPin: String, newPin: String) {
        val op = current ?: throw AccessDeniedException("Sign in first (option 47).")
        if (!hasher.verify(currentPin, op.pinSalt, op.pinHash)) {
            service.auditLog.log(op.id, AuditLog.Action.OPERATOR_SIGN_IN_FAILED, "Wrong current PIN for ${op.username} during PIN change")
            throw AuthenticationException("Current PIN is incorrect.")
        }
        if (currentPin == newPin) throw OperatorException("New PIN must differ from the current one.")
        setPin(op, newPin)
        op.mustChangePin = false
        service.auditLog.log(op.id, AuditLog.Action.OPERATOR_PIN_CHANGED, "${op.username} changed their PIN")
    }

    /** Admin sets a temporary PIN; the operator must replace it at next sign-in. */
    fun resetPin(username: String, temporaryPin: String) {
        val actor = require(Permission.OPERATOR_MANAGE)
        val op = requireOperator(username)
        setPin(op, temporaryPin)
        op.mustChangePin = true
        op.failedAttempts = 0
        op.lockedUntil = null
        service.auditLog.log(actor.id, AuditLog.Action.OPERATOR_PIN_CHANGED, "${actor.username} reset the PIN for ${op.username}")
    }

    fun unlock(username: String) {
        val actor = require(Permission.OPERATOR_MANAGE)
        val op = requireOperator(username)
        op.failedAttempts = 0
        op.lockedUntil = null
        service.auditLog.log(actor.id, AuditLog.Action.OPERATOR_PIN_CHANGED, "${actor.username} unlocked ${op.username}")
    }

    fun deactivate(username: String) {
        val actor = require(Permission.OPERATOR_MANAGE)
        val op = requireOperator(username)
        if (op.id == actor.id) throw OperatorException("You cannot deactivate yourself.")
        if (op.role == Operator.Role.ADMIN && activeAdmins().size <= 1) {
            throw OperatorException("Cannot deactivate the last active ADMIN.")
        }
        op.active = false
        if (session?.operator?.id == op.id) signOut()
        service.auditLog.log(actor.id, AuditLog.Action.OPERATOR_DEACTIVATED, "${actor.username} deactivated ${op.username}")
    }

    // ── Queries ───────────────────────────────────────────────────────

    fun find(username: String): Operator? =
        operatorIdByUsername[username.trim().lowercase()]?.let { operatorsById[it] }

    fun list(): List<Operator> = operatorsById.values.toList()

    fun activeAdmins(): List<Operator> =
        operatorsById.values.filter { it.active && it.role == Operator.Role.ADMIN }

    fun size(): Int = operatorsById.size

    // ── Snapshot support ──────────────────────────────────────────────

    /** Replace the directory; any live session ends because its operator record is gone. */
    internal fun replaceAll(operators: List<Operator>) {
        session = null
        operatorsById.clear()
        operatorIdByUsername.clear()
        operators.forEach { put(it) }
    }

    // ── Internals ─────────────────────────────────────────────────────

    private fun create(username: String, displayName: String, role: Operator.Role, pin: String): Operator {
        hasher.validatePin(pin)
        val handle = username.trim().lowercase()
        if (operatorIdByUsername.containsKey(handle)) throw OperatorException("Username '$handle' is already taken.")
        val salt = hasher.newSalt()
        val op = try {
            Operator(handle, displayName, role, hasher.hash(pin, salt), salt, createdAt = clock())
        } catch (e: IllegalArgumentException) {
            throw OperatorException(e.message ?: "Invalid operator details.")
        }
        put(op)
        return op
    }

    private fun setPin(op: Operator, pin: String) {
        hasher.validatePin(pin)
        val salt = hasher.newSalt()
        op.pinSalt = salt
        op.pinHash = hasher.hash(pin, salt)
    }

    private fun put(op: Operator) {
        operatorsById[op.id] = op
        operatorIdByUsername[op.username] = op.id
    }

    private fun requireOperator(username: String): Operator =
        find(username) ?: throw OperatorException("No operator named '${username.trim().lowercase()}'.")

    private companion object {
        const val GENERIC_FAILURE = "Invalid username or PIN."
    }
}
