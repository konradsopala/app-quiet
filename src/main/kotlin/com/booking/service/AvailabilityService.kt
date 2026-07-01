package com.booking.service

import com.booking.config.AppConfig
import com.booking.model.AvailabilitySlot
import com.booking.model.Resource
import com.booking.util.TextTable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Read-only search engine that finds open booking windows.
 *
 * The engine walks a date range, and within each day a grid of candidate
 * start times (spaced [SearchRequest.stepMinutes] apart) inside the business-
 * hours window, and asks [BookingService.overlappingBookings] how many
 * confirmed bookings already contend for each candidate window on each
 * resource. A candidate becomes an [AvailabilitySlot] when the resource's
 * capacity minus the overlap count still leaves room for the requested
 * booking.
 *
 * It is *pure* with respect to the booking set — it never mutates state and
 * holds no caches, so results always reflect the current bookings at call
 * time. That mirrors [LoyaltyEngine] and [AnalyticsEngine], the other
 * read-only derived-view services.
 *
 * Capacity accounting reuses the exact same overlap query the validator uses
 * when accepting a real booking, so a slot the engine reports as free is a
 * slot the create path will actually accept (barring the non-capacity rules —
 * advance notice, weekend blocking, duplicates — which are booking-specific
 * and out of scope for a pure availability sweep).
 */
class AvailabilityService(
    private val service: BookingService,
    private val config: AppConfig = AppConfig.DEFAULT
) {

    /**
     * A fully-specified availability query. All fields have sensible defaults
     * drawn from [AppConfig] so a caller can ask "what's free for a 60-minute
     * booking over the next week" with a single required argument plus a range.
     *
     *   * [durationMinutes] — length of the booking being placed.
     *   * [fromDate] / [toDate] — inclusive date window to scan.
     *   * [resourceId] — restrict to one resource; null scans every registered
     *     resource.
     *   * [earliestStart] / [latestEnd] — the daily window candidate slots must
     *     fall inside (a slot's whole duration must fit within it).
     *   * [stepMinutes] — spacing of the candidate start-time grid.
     *   * [limit] — cap on returned slots; the engine stops scanning further
     *     days once it has this many, and the earliest are kept.
     *   * [minRemainingCapacity] — require at least this much free headroom
     *     (useful to find slots that can still absorb a party or a second
     *     concurrent booking).
     *   * [includeWeekends] — when false, Saturdays and Sundays are skipped.
     *   * [onlyDaysOfWeek] — when non-null, restrict to these weekdays.
     */
    data class SearchRequest(
        val durationMinutes: Int,
        val fromDate: LocalDate,
        val toDate: LocalDate,
        val resourceId: String? = null,
        val earliestStart: LocalTime = AppConfig.DEFAULT.businessHoursOpen,
        val latestEnd: LocalTime = AppConfig.DEFAULT.businessHoursClose,
        val stepMinutes: Int = 30,
        val limit: Int = 20,
        val minRemainingCapacity: Int = 1,
        val includeWeekends: Boolean = true,
        val onlyDaysOfWeek: Set<DayOfWeek>? = null
    ) {
        init {
            require(durationMinutes > 0) { "durationMinutes must be positive." }
            require(!toDate.isBefore(fromDate)) { "toDate must be on or after fromDate." }
            require(earliestStart < latestEnd) { "earliestStart must be before latestEnd." }
            require(stepMinutes in 1..(24 * 60)) { "stepMinutes must be in 1..1440." }
            require(limit >= 1) { "limit must be at least 1." }
            require(minRemainingCapacity >= 1) { "minRemainingCapacity must be at least 1." }
            require(onlyDaysOfWeek == null || onlyDaysOfWeek.isNotEmpty()) {
                "onlyDaysOfWeek, when set, cannot be empty."
            }
            val windowMinutes = ChronoUnit.MINUTES.between(earliestStart, latestEnd)
            require(durationMinutes <= windowMinutes) {
                "durationMinutes ($durationMinutes) does not fit in the " +
                    "$earliestStart-$latestEnd window ($windowMinutes minutes)."
            }
        }
    }

    /**
     * Find open slots matching [request], earliest first.
     *
     * Ordering is deterministic: ascending by date, then by start time, then by
     * resource name (so a client scanning several rooms sees the same slot list
     * run to run). At most [SearchRequest.limit] slots are returned; the engine
     * fully processes each day in ascending order and stops after the first day
     * that pushes the running total to the limit, guaranteeing the kept slots
     * are genuinely the earliest available.
     */
    fun findSlots(request: SearchRequest): List<AvailabilitySlot> {
        val resources = resolveResources(request.resourceId)
        if (resources.isEmpty()) return emptyList()

        val startGrid = candidateStarts(request)
        if (startGrid.isEmpty()) return emptyList()

        val collected = mutableListOf<AvailabilitySlot>()
        var date = request.fromDate
        while (!date.isAfter(request.toDate)) {
            if (dateAllowed(date, request)) {
                val perDay = mutableListOf<AvailabilitySlot>()
                for (resource in resources) {
                    for (start in startGrid) {
                        val slot = evaluate(date, start, resource, request)
                        if (slot != null) perDay.add(slot)
                    }
                }
                // Sort within the day so the global list stays ordered even
                // though resources are the outer loop above.
                perDay.sortWith(compareBy({ it.startTime }, { it.resourceName }))
                collected.addAll(perDay)
                if (collected.size >= request.limit) break
            }
            date = date.plusDays(1)
        }
        return collected.take(request.limit)
    }

    /**
     * The single earliest open slot matching [request], or null if the window
     * has none. Convenience wrapper over [findSlots] with the limit forced to 1
     * so the scan bails out on the first day with any availability.
     */
    fun findNextAvailable(request: SearchRequest): AvailabilitySlot? =
        findSlots(request.copy(limit = 1)).firstOrNull()

    /**
     * Reduce a dense slot list to non-overlapping picks per resource, greedily
     * keeping the earliest of each cluster. Useful when the grid step is finer
     * than the duration (e.g. 30-minute steps for 60-minute bookings would
     * otherwise report 10:00, 10:30, 11:00 … as separate overlapping options).
     *
     * Input need not be sorted; output is ascending by (date, start, resource).
     */
    fun nonOverlapping(slots: List<AvailabilitySlot>): List<AvailabilitySlot> {
        val ordered = slots.sortedWith(
            compareBy({ it.date }, { it.startTime }, { it.resourceName })
        )
        val kept = mutableListOf<AvailabilitySlot>()
        for (slot in ordered) {
            if (kept.none { it.overlaps(slot) }) kept.add(slot)
        }
        return kept
    }

    /**
     * Per-day count of distinct open start-times summed across the scanned
     * resources — a lightweight "how busy is each day" view. Days with no
     * availability are included with a zero so gaps are visible. Ignores
     * [SearchRequest.limit] (it always sweeps the whole range) but honours the
     * weekday filters.
     */
    fun dailyOpenCounts(request: SearchRequest): Map<LocalDate, Int> {
        val resources = resolveResources(request.resourceId)
        val startGrid = candidateStarts(request)
        val counts = linkedMapOf<LocalDate, Int>()
        var date = request.fromDate
        while (!date.isAfter(request.toDate)) {
            if (dateAllowed(date, request)) {
                var open = 0
                for (resource in resources) {
                    for (start in startGrid) {
                        if (evaluate(date, start, resource, request) != null) open++
                    }
                }
                counts[date] = open
            }
            date = date.plusDays(1)
        }
        return counts
    }

    /**
     * Render [findSlots] as a fixed-width table for the CLI. Kept here rather
     * than in the App so the column layout lives next to the data that feeds
     * it. Returns a friendly message when nothing is free.
     */
    fun renderSlotTable(request: SearchRequest): String {
        val slots = findSlots(request)
        if (slots.isEmpty()) {
            return "No open ${request.durationMinutes}-minute slots between " +
                "${request.fromDate} and ${request.toDate}."
        }
        val table = TextTable(listOf("#", "Date", "Start", "End", "Resource", "Free"))
            .align(0, TextTable.Align.RIGHT)
            .align(5, TextTable.Align.RIGHT)
        slots.forEachIndexed { i, s ->
            table.row(
                (i + 1).toString(),
                s.date.toString(),
                s.startTime.toString(),
                s.endTime.toString(),
                s.resourceName,
                "${s.remainingCapacity}/${s.totalCapacity}"
            )
        }
        return table.render()
    }

    /**
     * Suggest open slots near a desired-but-unavailable window.
     *
     * Given a slot the caller wanted — [date] at [startTime] for
     * [durationMinutes] on [resourceId] (or any resource when null) — return up
     * to [maxSuggestions] open alternatives, ordered by closeness: same-day
     * options ranked by how far their start is from [startTime], then the
     * following days in order. Scans [searchDays] days starting at [date].
     *
     * Intended for the CLI to answer a capacity rejection with "that's full,
     * but here's the nearest thing that isn't" rather than a dead end.
     */
    fun suggestAlternatives(
        date: LocalDate,
        startTime: LocalTime,
        durationMinutes: Int,
        resourceId: String? = null,
        maxSuggestions: Int = 5,
        searchDays: Int = 7
    ): List<AvailabilitySlot> {
        require(maxSuggestions >= 1) { "maxSuggestions must be at least 1." }
        require(searchDays >= 1) { "searchDays must be at least 1." }
        val request = SearchRequest(
            durationMinutes = durationMinutes,
            fromDate = date,
            toDate = date.plusDays((searchDays - 1).toLong()),
            resourceId = resourceId,
            // Cast a wide net; we re-rank and trim below.
            limit = maxSuggestions * 64,
            stepMinutes = 30
        )
        val desiredMinute = startTime.toSecondOfDay() / 60
        return findSlots(request)
            // Drop the exact desired window — the caller already knows it's taken.
            .filterNot { it.date == date && it.startTime == startTime }
            .sortedWith(
                compareBy(
                    { ChronoUnit.DAYS.between(date, it.date) },
                    { kotlin.math.abs(it.startTime.toSecondOfDay() / 60 - desiredMinute) },
                    { it.resourceName }
                )
            )
            .take(maxSuggestions)
    }

    /**
     * A compact statistical view of how open a search window is — the raw
     * material for a "how bookable is next week?" summary.
     *
     *   * [candidateWindows] — total (resource, day, start-time) cells evaluated.
     *   * [openWindows] — how many of those had room for the booking.
     *   * [busiestDate] / [quietestDate] — the days with the fewest / most open
     *     cells (null when the range has no allowed days).
     *   * [openRate] — [openWindows] / [candidateWindows], 0.0 when nothing was
     *     evaluated.
     */
    data class CoverageSummary(
        val candidateWindows: Int,
        val openWindows: Int,
        val busiestDate: LocalDate?,
        val quietestDate: LocalDate?,
        val openRate: Double
    ) {
        override fun toString(): String {
            val pct = (openRate * 100).let { "%.1f".format(it) }
            val busy = busiestDate?.toString() ?: "(none)"
            val quiet = quietestDate?.toString() ?: "(none)"
            return "$openWindows/$candidateWindows windows open ($pct%); " +
                "busiest $busy, quietest $quiet"
        }
    }

    /**
     * Summarise availability across [request]'s whole range (ignoring its
     * [SearchRequest.limit], since a summary must see everything). "Busiest"
     * means fewest open cells; "quietest" means most.
     */
    fun coverageSummary(request: SearchRequest): CoverageSummary {
        val resources = resolveResources(request.resourceId)
        val grid = candidateStarts(request)
        val perDay = linkedMapOf<LocalDate, Int>()
        var candidate = 0
        var open = 0
        var date = request.fromDate
        while (!date.isAfter(request.toDate)) {
            if (dateAllowed(date, request)) {
                var dayOpen = 0
                for (resource in resources) {
                    for (start in grid) {
                        candidate++
                        if (evaluate(date, start, resource, request) != null) {
                            open++; dayOpen++
                        }
                    }
                }
                perDay[date] = dayOpen
            }
            date = date.plusDays(1)
        }
        val busiest = perDay.entries.minByOrNull { it.value }?.key
        val quietest = perDay.entries.maxByOrNull { it.value }?.key
        val rate = if (candidate == 0) 0.0 else open.toDouble() / candidate
        return CoverageSummary(candidate, open, busiest, quietest, rate)
    }

    /**
     * Render a date × hour heatmap of open start-slots, counting how many
     * candidate windows begin in each clock hour on each day (summed across the
     * scanned resources). Columns are the distinct start hours in the grid;
     * cells show the open count, or "." for a fully-booked hour. Honours the
     * weekday filters but, like [coverageSummary], sweeps the whole range.
     */
    fun renderHeatmap(request: SearchRequest): String {
        val resources = resolveResources(request.resourceId)
        val grid = candidateStarts(request)
        if (grid.isEmpty()) return "No candidate start times fit the requested window."
        val hours = grid.map { it.hour }.distinct().sorted()

        val table = TextTable(listOf("Date") + hours.map { "%02d".format(it) })
        hours.indices.forEach { table.align(it + 1, TextTable.Align.RIGHT) }

        var date = request.fromDate
        var anyRow = false
        while (!date.isAfter(request.toDate)) {
            if (dateAllowed(date, request)) {
                anyRow = true
                val byHour = hours.associateWith { 0 }.toMutableMap()
                for (resource in resources) {
                    for (start in grid) {
                        if (evaluate(date, start, resource, request) != null) {
                            byHour[start.hour] = (byHour[start.hour] ?: 0) + 1
                        }
                    }
                }
                val cells = hours.map { h -> byHour[h]?.takeIf { it > 0 }?.toString() ?: "." }
                table.row(date.toString(), *cells.toTypedArray())
            }
            date = date.plusDays(1)
        }
        return if (anyRow) table.render() else "No days in range matched the weekday filters."
    }

    /**
     * The result of checking whether a fixed time-of-day stays bookable across
     * a recurring cadence — the availability counterpart to
     * [RecurringBookingService.createSeries].
     *
     *   * [openDates] — occurrences where the slot has room.
     *   * [blockedDates] — occurrences where it doesn't.
     *   * [fullyAvailable] — true when nothing is blocked, i.e. the whole series
     *     could be booked at this time.
     */
    data class RecurringAvailability(
        val startTime: LocalTime,
        val durationMinutes: Int,
        val resourceId: String?,
        val openDates: List<LocalDate>,
        val blockedDates: List<LocalDate>
    ) {
        val requested: Int get() = openDates.size + blockedDates.size
        val fullyAvailable: Boolean get() = blockedDates.isEmpty() && openDates.isNotEmpty()

        override fun toString(): String {
            val head = "$startTime for ${durationMinutes}m: " +
                "${openDates.size}/$requested occurrence(s) open"
            return if (blockedDates.isEmpty()) "$head — fully available"
            else "$head — blocked on ${blockedDates.joinToString(", ")}"
        }
    }

    /**
     * Check whether a fixed [startTime]/[durationMinutes] slot is open across
     * [occurrences] dates spaced by [cadence], starting at [firstDate].
     *
     * A resource-specific check ([resourceId] non-null) asks whether *that*
     * resource has room on each date; an all-resource check ([resourceId] null)
     * asks whether *any* registered resource does — matching how the create
     * path would place an unresourced booking. Unlike the range sweeps this
     * ignores business hours: the caller is naming an exact time, so honouring
     * it is their call.
     *
     * This is a pure read; it never creates the series. Feed the result to
     * [RecurringBookingService.createSeries] once the operator confirms.
     */
    fun findRecurringSlots(
        firstDate: LocalDate,
        occurrences: Int,
        cadence: RecurringBookingService.Cadence,
        startTime: LocalTime,
        durationMinutes: Int,
        resourceId: String? = null,
        minRemainingCapacity: Int = 1
    ): RecurringAvailability {
        require(occurrences >= 1) { "occurrences must be at least 1." }
        require(durationMinutes > 0) { "durationMinutes must be positive." }
        require(minRemainingCapacity >= 1) { "minRemainingCapacity must be at least 1." }
        val resources = resolveResources(resourceId)
        val open = mutableListOf<LocalDate>()
        val blocked = mutableListOf<LocalDate>()
        var date = firstDate
        repeat(occurrences) {
            val fits = resources.any { resource ->
                val overlaps = service.overlappingBookings(
                    date, startTime, durationMinutes, excludeId = null, resourceId = resource.id
                ).size
                (resource.capacity - overlaps) >= minRemainingCapacity
            }
            if (fits) open.add(date) else blocked.add(date)
            date = cadence.next(date)
        }
        return RecurringAvailability(startTime, durationMinutes, resourceId, open, blocked)
    }

    /**
     * The earliest open slot on *each* scanned resource within [request]'s
     * range, keyed by resource name and ordered by that earliest slot (soonest
     * first). Resources with no availability in range are omitted. Lets an
     * operator compare "soonest opening per room" directly instead of eyeballing
     * a merged, interleaved list.
     */
    fun firstFitPerResource(request: SearchRequest): Map<String, AvailabilitySlot> {
        val resources = resolveResources(request.resourceId)
        val fits = mutableListOf<Pair<String, AvailabilitySlot>>()
        for (resource in resources) {
            val slot = findNextAvailable(request.copy(resourceId = resource.id))
            if (slot != null) fits.add(resource.name to slot)
        }
        // Sort by soonest opening, then resource name for a stable tie-break.
        fits.sortWith(compareBy({ it.second.date }, { it.second.startTime }, { it.first }))
        val ordered = linkedMapOf<String, AvailabilitySlot>()
        for ((name, slot) in fits) ordered[name] = slot
        return ordered
    }

    /** Render [firstFitPerResource] as a table; friendly message when all full. */
    fun renderFirstFitPerResource(request: SearchRequest): String {
        val fits = firstFitPerResource(request)
        if (fits.isEmpty()) {
            return "No resource has an open ${request.durationMinutes}-minute slot " +
                "between ${request.fromDate} and ${request.toDate}."
        }
        val table = TextTable(listOf("Resource", "Date", "Start", "End", "Free"))
            .align(4, TextTable.Align.RIGHT)
        for ((name, slot) in fits) {
            table.row(
                name, slot.date.toString(), slot.startTime.toString(),
                slot.endTime.toString(), "${slot.remainingCapacity}/${slot.totalCapacity}"
            )
        }
        return table.render()
    }

    // ── internals ──────────────────────────────────────────────────

    /**
     * Evaluate one (date, start, resource) candidate. Returns a slot when the
     * resource has room for the requested booking after existing overlaps, or
     * null when the window is full or the booking wouldn't fit the resource's
     * capacity at all.
     */
    private fun evaluate(
        date: LocalDate,
        start: LocalTime,
        resource: Resource,
        request: SearchRequest
    ): AvailabilitySlot? {
        val overlaps = service.overlappingBookings(
            date, start, request.durationMinutes, excludeId = null, resourceId = resource.id
        ).size
        val remaining = resource.capacity - overlaps
        if (remaining < request.minRemainingCapacity) return null
        return AvailabilitySlot(
            resourceId = resource.id,
            resourceName = resource.name,
            date = date,
            startTime = start,
            durationMinutes = request.durationMinutes,
            remainingCapacity = remaining,
            totalCapacity = resource.capacity
        )
    }

    /**
     * The resources to scan: a single one when [resourceId] is given (throws on
     * an unknown id so a typo doesn't silently return "nothing free"), or every
     * registered resource otherwise.
     */
    private fun resolveResources(resourceId: String?): List<Resource> {
        if (resourceId == null) return service.resources.list()
        val resource = service.resources.find(resourceId)
            ?: throw IllegalArgumentException("Unknown resource id: $resourceId")
        return listOf(resource)
    }

    /**
     * The grid of candidate start times for a day: every [SearchRequest.stepMinutes]
     * from [SearchRequest.earliestStart] onward whose whole duration still ends
     * on or before [SearchRequest.latestEnd]. Computed in integer minutes-of-day
     * so a late window can never wrap past midnight.
     */
    private fun candidateStarts(request: SearchRequest): List<LocalTime> {
        val earliest = request.earliestStart.toSecondOfDay() / 60
        val latest = request.latestEnd.toSecondOfDay() / 60
        val starts = mutableListOf<LocalTime>()
        var minute = earliest
        while (minute + request.durationMinutes <= latest) {
            starts.add(LocalTime.ofSecondOfDay(minute * 60L))
            minute += request.stepMinutes
        }
        return starts
    }

    /** Apply the weekend / weekday filters to a candidate date. */
    private fun dateAllowed(date: LocalDate, request: SearchRequest): Boolean {
        val dow = date.dayOfWeek
        if (!request.includeWeekends && (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)) {
            return false
        }
        if (request.onlyDaysOfWeek != null && dow !in request.onlyDaysOfWeek) {
            return false
        }
        return true
    }
}
