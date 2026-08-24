package com.booking.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * A staff member who can be assigned to a [Booking] via
 * [Booking.staffId].
 *
 * Staff are deliberately separate from [Resource]: a resource is a
 * *place* (a room, a chair) with a concurrency cap, while a staff
 * member is a *person* whose availability is governed by their
 * [com.booking.service.StaffService]-managed [Shift]s rather than a
 * capacity number. A booking can reference both a resource and a
 * staff member independently.
 */
class Staff(
    name: String,
    role: String,
    email: String? = null,
    phone: String? = null,
    skills: Set<String> = emptySet(),
    val hiredAt: LocalDateTime = LocalDateTime.now(),
    val id: String = "staff_" + UUID.randomUUID().toString().replace("-", "").take(16)
) {
    var name: String = name
        set(value) {
            require(value.isNotBlank()) { "Staff name cannot be blank." }
            field = value.trim()
        }

    var role: String = role
        set(value) {
            require(value.isNotBlank()) { "Staff role cannot be blank." }
            field = value.trim()
        }

    var email: String? = email
    var phone: String? = phone

    var active: Boolean = true
        private set

    private val _skills: MutableSet<String> = skills
        .asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toMutableSet()
    val skills: Set<String> get() = _skills.toSet()

    init {
        require(name.isNotBlank()) { "Staff name cannot be blank." }
        require(role.isNotBlank()) { "Staff role cannot be blank." }
        this.name = name.trim()
        this.role = role.trim()
    }

    fun addSkill(skill: String): Boolean {
        require(skill.isNotBlank()) { "skill cannot be blank" }
        return _skills.add(skill.trim().lowercase())
    }

    fun removeSkill(skill: String): Boolean = _skills.remove(skill.trim().lowercase())

    fun hasSkill(skill: String): Boolean = _skills.contains(skill.trim().lowercase())

    fun deactivate() {
        active = false
    }

    fun reactivate() {
        active = true
    }

    /**
     * Restore the active flag from a persisted snapshot without going
     * through [deactivate]/[reactivate] (no side effects beyond the flag
     * itself).
     */
    internal fun restoreActive(value: Boolean) {
        active = value
    }

    override fun toString(): String {
        val statusLabel = if (active) "ACTIVE" else "INACTIVE"
        val skillSuffix = if (_skills.isEmpty()) "" else " | skills:[${_skills.sorted().joinToString(",")}]"
        val contactParts = listOfNotNull(email, phone)
        val contactSuffix = if (contactParts.isEmpty()) "" else " | ${contactParts.joinToString(", ")}"
        return "[$id] $name ($role) - $statusLabel$skillSuffix$contactSuffix"
    }
}
