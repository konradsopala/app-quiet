package com.booking.security

import com.booking.model.Operator

/**
 * Every privileged thing the CLI can do, named from the operator's point of
 * view. Keep these coarse: a permission is a menu-level capability, not a
 * per-record ACL.
 */
enum class Permission(val description: String) {
    GIFT_CARD_VIEW("Look up a card's balance and statement"),
    GIFT_CARD_ISSUE("Sell a new gift card"),
    GIFT_CARD_RELOAD("Add value to an existing card"),
    GIFT_CARD_REDEEM("Apply card value to a booking"),
    GIFT_CARD_VOID("Void a card and write off its balance"),
    GIFT_CARD_VOID_HIGH_VALUE("Approve voiding a card above the approval threshold"),
    GIFT_CARD_REPORT("Run the liability report"),
    GIFT_CARD_EXPORT("Export cards or the ledger to CSV"),
    OPERATOR_MANAGE("Register, reset, unlock or deactivate operators"),
    SNAPSHOT_LOAD("Replace live state from a snapshot file")
}

/**
 * Static role → permission grants. Roles are cumulative: a MANAGER can do
 * everything a CASHIER can, an ADMIN everything a MANAGER can. The table is
 * the single place to look when someone asks "who can void a card?".
 */
object AccessPolicy {

    private val cashier: Set<Permission> = setOf(
        Permission.GIFT_CARD_VIEW,
        Permission.GIFT_CARD_ISSUE,
        Permission.GIFT_CARD_RELOAD,
        Permission.GIFT_CARD_REDEEM
    )

    private val manager: Set<Permission> = cashier + setOf(
        Permission.GIFT_CARD_VOID,
        Permission.GIFT_CARD_VOID_HIGH_VALUE,
        Permission.GIFT_CARD_REPORT,
        Permission.GIFT_CARD_EXPORT
    )

    private val admin: Set<Permission> = manager + setOf(
        Permission.OPERATOR_MANAGE,
        Permission.SNAPSHOT_LOAD
    )

    private val grants: Map<Operator.Role, Set<Permission>> = mapOf(
        Operator.Role.CASHIER to cashier,
        Operator.Role.MANAGER to manager,
        Operator.Role.ADMIN to admin
    )

    fun permissionsFor(role: Operator.Role): Set<Permission> = grants.getValue(role)

    fun allows(role: Operator.Role, permission: Permission): Boolean =
        permission in permissionsFor(role)

    /** Lowest role that holds [permission]; used in "denied" messages so the operator knows who to ask. */
    fun minimumRoleFor(permission: Permission): Operator.Role =
        Operator.Role.values().first { allows(it, permission) }

    /** Human-readable matrix for the operator-management screen. */
    fun describe(): String = buildString {
        appendLine("Permission".padEnd(28) + Operator.Role.values().joinToString("  ") { it.name.padEnd(8) })
        for (p in Permission.values()) {
            append(p.name.padEnd(28))
            append(Operator.Role.values().joinToString("  ") { (if (allows(it, p)) "✓" else "·").padEnd(8) })
            appendLine()
        }
    }
}
