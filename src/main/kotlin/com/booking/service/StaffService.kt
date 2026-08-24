package com.booking.service

import com.booking.model.Booking
import com.booking.model.Shift
import com.booking.model.Staff
import java.io.FileWriter
import java.io.PrintWriter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Registry of [Staff] members plus the [Shift]s that define when each
 * one is available, and the availability check
 * [BookingValidator] consults before letting a booking claim a staff
 * member's time.
 *
 * Two independent things gate an assignment:
 *   1. **Coverage** — some [Shift] for that staff member must fully
 *      contain the requested date/time window.
 *   2. **Conflict** — no other `CONFIRMED` booking already assigned to
 *      that staff member may overlap the requested window.
 *
 * Both checks operate on the live [BookingService] snapshot, so this
 * class is constructed with one rather than owning bookings itself.
 */
class StaffService(private val service: BookingService) {

    private val registry = linkedMapOf<String, Staff>()
    private val shifts = linkedMapOf<String, Shift>()

    sealed class AvailabilityResult {
        object Available : AvailabilityResult()
        data class Unavailable(val reason: String) : AvailabilityResult()
    }

    data class WeeklyShiftResult(val created: List<Shift>, val skippedDates: List<LocalDate>)

    data class Workload(val staffId: String, val staffName: String, val confirmedBookings: Int, val bookedMinutes: Int)

    // ── Staff CRUD ───────────────────────────────────────────────────

    fun register(
        name: String,
        role: String,
        email: String? = null,
        phone: String? = null,
        skills: Set<String> = emptySet()
    ): Staff {
        val staff = Staff(name = name, role = role, email = email, phone = phone, skills = skills)
        registry[staff.id] = staff
        service.auditLog.log(staff.id, AuditLog.Action.STAFF_REGISTERED, staff.toString())
        return staff
    }

    fun find(id: String): Staff? = registry[id]

    fun list(): List<Staff> = registry.values.toList()

    fun listActive(): List<Staff> = registry.values.filter { it.active }

    fun findByRole(role: String): List<Staff> =
        registry.values.filter { it.role.equals(role, ignoreCase = true) }

    fun findBySkill(skill: String): List<Staff> =
        registry.values.filter { it.hasSkill(skill) }

    fun deactivate(id: String): Boolean {
        val staff = registry[id] ?: return false
        staff.deactivate()
        service.auditLog.log(id, AuditLog.Action.STAFF_DEACTIVATED, "${staff.name} deactivated")
        return true
    }

    fun reactivate(id: String): Boolean {
        val staff = registry[id] ?: return false
        staff.reactivate()
        service.auditLog.log(id, AuditLog.Action.STAFF_DEACTIVATED, "${staff.name} reactivated")
        return true
    }

    // ── Shift management ─────────────────────────────────────────────

    class ShiftException(message: String) : RuntimeException(message)

    /**
     * Adds a single shift for [staffId]. Throws [ShiftException] if the
     * staff id is unknown or the new window overlaps an existing shift
     * for that same staff member (a staff member can't be scheduled
     * twice at once any more than a booking can double-book a resource).
     */
    fun addShift(staffId: String, date: LocalDate, startTime: LocalTime, durationMinutes: Int): Shift {
        val staff = registry[staffId] ?: throw ShiftException("Unknown staff id: $staffId")
        val candidate = Shift(staffId, date, startTime, durationMinutes)
        val overlap = shiftsForStaff(staffId).firstOrNull { it.overlaps(candidate) }
        if (overlap != null) {
            throw ShiftException("Shift overlaps existing shift ${overlap.id} (${overlap.date} ${overlap.startTime}-${overlap.endTime}).")
        }
        shifts[candidate.id] = candidate
        service.auditLog.log(staffId, AuditLog.Action.SHIFT_ADDED, "${staff.name}: $candidate")
        return candidate
    }

    /**
     * Materialises one [Shift] per date between [from] and [to] (inclusive)
     * that falls on one of [daysOfWeek], at [startTime] for
     * [durationMinutes]. Dates that would overlap an already-scheduled
     * shift are skipped rather than aborting the whole batch, and
     * reported back in [WeeklyShiftResult.skippedDates].
     */
    fun addWeeklyShifts(
        staffId: String,
        from: LocalDate,
        to: LocalDate,
        daysOfWeek: Set<DayOfWeek>,
        startTime: LocalTime,
        durationMinutes: Int
    ): WeeklyShiftResult {
        require(!from.isAfter(to)) { "from must be on or before to" }
        require(daysOfWeek.isNotEmpty()) { "daysOfWeek cannot be empty" }
        if (registry[staffId] == null) throw ShiftException("Unknown staff id: $staffId")

        val created = mutableListOf<Shift>()
        val skipped = mutableListOf<LocalDate>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            if (cursor.dayOfWeek in daysOfWeek) {
                try {
                    created.add(addShift(staffId, cursor, startTime, durationMinutes))
                } catch (e: ShiftException) {
                    skipped.add(cursor)
                }
            }
            cursor = cursor.plusDays(1)
        }
        return WeeklyShiftResult(created, skipped)
    }

    fun removeShift(shiftId: String): Boolean {
        val shift = shifts.remove(shiftId) ?: return false
        service.auditLog.log(shift.staffId, AuditLog.Action.SHIFT_REMOVED, shift.toString())
        return true
    }

    fun shiftsForStaff(staffId: String): List<Shift> =
        shifts.values.filter { it.staffId == staffId }.sortedWith(compareBy({ it.date }, { it.startTime }))

    fun shiftsOn(date: LocalDate): List<Shift> =
        shifts.values.filter { it.date == date }.sortedBy { it.startTime }

    fun allShifts(): List<Shift> = shifts.values.toList()

    // ── Availability ─────────────────────────────────────────────────

    /**
     * Checks whether [staffId] can be assigned to a booking on [date]
     * from [startTime] for [durationMinutes]. [excludeBookingId] lets a
     * reschedule of an existing booking check availability without
     * tripping over its own current slot.
     */
    fun checkAvailability(
        staffId: String,
        date: LocalDate,
        startTime: LocalTime,
        durationMinutes: Int,
        excludeBookingId: String? = null
    ): AvailabilityResult {
        val staff = registry[staffId] ?: return AvailabilityResult.Unavailable("Unknown staff id: $staffId")
        if (!staff.activ) return AvailabilityResult.Unavailable("${staff.name} is not active.")

        val endTime = startTime.plusMinutes(durationMinutes.toLong())
        val covered = shiftsForStaff(staffId).any { it.covers(date, startTime, endTime) }
        if (!covered) {
            return AvailabilityResult.Unavailable("${staff.name} has no shift covering $date $startTime-$endTime.")
        }

        val conflict = service.listBookings()
            .filter { it.status == Booking.Status.CONFIRMED }
            .filter { it.staffId == staffId }
            .filter { it.id != excludeBookingId }
            .filter { it.date == date }
            .firstOrNull { existing ->
                startTime < existing.endTime && existing.startTime < endTime
            }
        if (conflict != null) {
            return AvailabilityResult.Unavailable(
                "${staff.name} is already assigned to booking ${conflict.id} at ${conflict.startTime}-${conflict.endTime}."
            )
        }

        return AvailabilityResult.Available
    }

    // ── Reporting ────────────────────────────────────────────────────

    /** Confirmed-booking count and booked minutes per staff member, sorted busiest-first. */
    fun workload(): List<Workload> {
        val confirmed = service.listBookings().filter { it.status == Booking.Status.CONFIRMED }
        return registry.values.map { staff ->
            val assigned = confirmed.filter { it.staffId == staff.id }
            Workload(
                staffId = staff.id,
                staffName = staff.name,
                confirmedBookings = assigned.size,
                bookedMinutes = assigned.sumOf { it.durationMinutes }
            )
        }.sortedByDescending { it.confirmedBookings }
    }

    // ── Export ───────────────────────────────────────────────────────

    /**
     * Writes the staff directory plus per-staff workload to [filePath] as
     * CSV, mirroring the escaping convention used by
     * [BookingService.exportToCsv].
     */
    fun exportToCsv(filePath: String) {
        val workloadById = workload().associateBy { it.staffId }
        PrintWriter(FileWriter(filePath)).use { writer ->
            writer.println("id,name,role,status,skills,email,phone,confirmed_bookings,booked_minutes")
            for (member in registry.values) {
                val w = workloadById[member.id]
                writer.printf(
                    "%s,%s,%s,%s,%s,%s,%s,%d,%d%n",
                    escape(member.id), escape(member.name), escape(member.role),
                    if (member.active) "ACTIVE" else "INACTIVE",
                    escape(member.skills.sorted().joinToString(";")),
                    escape(member.email ?: ""), escape(member.phone ?: ""),
                    w?.confirmedBookings ?: 0, w?.bookedMinutes ?: 0
                )
            }
        }
        service.auditLog.log("SYSTEM", AuditLog.Action.EXPORTED, "Exported ${registry.size} staff record(s) to $filePath")
    }

    private fun escape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    // ── Snapshot restore ─────────────────────────────────────────────

    internal fun replaceAll(newStaff: List<Staff>, newShifts: List<Shift>) {
        registry.clear()
        newStaff.forEach { registry[it.id] = it }
        shifts.clear()
        newShifts.forEach { shifts[it.id] = it }
    }
}
