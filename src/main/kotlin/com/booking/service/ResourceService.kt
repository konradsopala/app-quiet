package com.booking.service

import com.booking.config.AppConfig
import com.booking.model.Resource

/**
 * Registry of bookable [Resource]s.
 *
 * A single default resource (`MAIN`) is created at construction time
 * with the [AppConfig.defaultCapacity] baseline so existing callers
 * that never specify a resource still resolve to something sensible.
 *
 * The default resource has a stable id (`MAIN_RESOURCE_ID`) so other
 * services can reference it without a directory lookup; deleting it
 * is allowed but [defaultResource] will then return null and the
 * `Booking.resourceId = null` code path is the only thing that still
 * works.
 */
class ResourceService(
    config: AppConfig = AppConfig.DEFAULT,
    private val auditLog: AuditLog? = null
) {

    companion object {
        /** Stable id for the system-default resource. */
        const val MAIN_RESOURCE_ID = "res_main"
    }

    private val registry = linkedMapOf<String, Resource>()

    init {
        val main = Resource(name = "Main", capacity = config.defaultCapacity, id = MAIN_RESOURCE_ID)
        registry[main.id] = main
        auditLog?.log(main.id, AuditLog.Action.RESOURCE_REGISTERED, "Default resource: $main")
    }

    // ── CRUD ────────────────────────────────────────────────────────

    fun register(name: String, capacity: Int = 1): Resource {
        val resource = Resource(name = name, capacity = capacity)
        registry[resource.id] = resource
        auditLog?.log(resource.id, AuditLog.Action.RESOURCE_REGISTERED, resource.toString())
        return resource
    }

    fun find(id: String): Resource? = registry[id]

    fun list(): List<Resource> = registry.values.toList()

    /** Returns the default resource, or null if it has been deleted. */
    fun defaultResource(): Resource? = registry[MAIN_RESOURCE_ID]

    /**
     * Set the capacity on a registered resource. Returns the updated
     * resource, or null if the id is unknown. Audit-logged as a
     * RESOURCE_REGISTERED so the history still reads chronologically.
     */
    fun setCapacity(id: String, capacity: Int): Resource? {
        val resource = registry[id] ?: return null
        resource.capacity = capacity
        auditLog?.log(id, AuditLog.Action.RESOURCE_REGISTERED, "Capacity → $capacity")
        return resource
    }

    fun delete(id: String): Boolean {
        val removed = registry.remove(id) != null
        if (removed) auditLog?.log(id, AuditLog.Action.RESOURCE_DELETED, "Removed from registry")
        return removed
    }

    fun size(): Int = registry.size
}
