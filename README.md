# Booking System

A command-line booking system built with Kotlin. Supports full booking lifecycle management with validation, audit logging, reporting, advanced search, time-slot scheduling with configurable capacity, persistent price quotes, recurring booking series, a capacity-aware FIFO waitlist, payment intents (Stripe-style, with a pluggable processor), iCalendar (`.ics`) export, a standalone customer directory, a derived-metrics layer (busiest day, capacity utilisation, top customers), scheduled multi-channel reminders, a usage-analytics engine, and loyalty tiers.

## Prerequisites

- Kotlin 1.9+
- JDK 17+

## Build & Run

```bash
find src -name "*.kt" -print0 | xargs -0 kotlinc -include-runtime -d booking.jar
java -jar booking.jar
```

## Features

- **Create booking** — date, start time, duration, with full validation (duplicates, advance notice, weekend rules, capacity)
- **Time slots & capacity** — bookings have a start time and duration; configurable per-system capacity rejects new/rescheduled slots whose overlapping confirmed bookings would exceed it
- **List bookings** — view all bookings with status and (if quoted) total
- **Find booking** — look up a booking by ID
- **Cancel booking** — cancel an existing booking by ID
- **Search by customer** — case-insensitive partial name matching
- **Update/reschedule** — modify date, time, duration, or description with validation
- **Statistics** — total, confirmed, cancelled counts, capacity, and quoted revenue
- **Export to CSV** — save bookings (including times and quote totals) to a CSV file
- **Generate reports** — summary, daily schedule, or per-customer reports (console or file)
- **Advanced search** — filter by status, date range, customer; sort and limit results
- **Audit log** — view full mutation history across all bookings (create, update, cancel, export, quote, waitlist, promote, series cancel, payment intent/succeeded/failed/refunded, iCal exported)
- **Booking history** — view the change trail for a single booking
- **Price quotes** — quote a booking and persist the result on the booking itself, so totals show up in listings, reports, and CSV exports
- **Recurring series** — create N occurrences on a daily/weekly/biweekly/monthly cadence; each occurrence is independently validated, collisions are skipped and reported. Every booking in the series carries a shared `seriesId` so the whole series can be cancelled in one go.
- **Waitlist** — when a new booking is rejected solely on capacity, the CLI offers to add it to a FIFO waitlist. After every cancellation (or capacity bump) the system walks the queue and promotes any entry whose slot now passes full validation, in order. Waitlist entries can be listed and removed manually.
- **Payment intents** — Stripe-style flow: a booking with a quote can have a `PaymentIntent` created, then `confirm`ed via a pluggable `PaymentProcessor` (a `MockPaymentProcessor` ships in-tree; swap in a real gateway by implementing the interface). Successful intents move to `SUCCEEDED` and can be `refund`ed; declines move to `FAILED`. Every transition is recorded in the audit log and reflected in `netSettled`. Cancelling a booking (single or whole series) **auto-refunds** any `SUCCEEDED` intents attached to it, so funds aren't left held when the slot goes away.
- **iCalendar export** — render any booking, or all bookings, as RFC 5545 `.ics` content with proper TEXT escaping (commas, semicolons, newlines, backslashes), 75-octet line folding, CRLF terminators, and `STATUS:CONFIRMED|CANCELLED` so cancelled events still surface in calendar clients.
- **Booking metadata** — bookings can carry a `Set<String>` of free-form `tags` (lower-cased + comma-rejecting on add), a staff-only `notes` field, and an `internalReference` string for cross-system linking. Tags flow through CSV export (semicolon-joined column), iCal export (`CATEGORIES` line, merged with the `SERIES-*` system tag), `BookingFilter` (case-insensitive AND-composing `byTag`), summary reports (tag-usage section), and the audit log. **Notes are intentionally excluded from iCal export** since the calendar is the customer-visible artefact; they surface in the customer report instead. `internalReference` rides out as the custom iCal property `X-BOOKING-REF` and supports substring search via `BookingFilter.byInternalReference`.
- **Reminders** — each new booking is expanded against a set of declarative `ReminderRule`s (offset-before-start, channel, priority, message template) into scheduled notifications. Reminders whose fire time has already passed are skipped and reported. This is a self-contained scheduling layer on top of the dedicated `NotificationService` queue, distinct from the event-fanout `NotificationDispatcher`.
- **Notifications (reminder bus)** — a synchronous, multi-channel dispatcher (Email / SMS / Push / Console) with per-channel length limits, priority-ordered flushing, delivery stats, and per-booking cancellation. Channel sinks are pluggable.
- **Analytics** — read-only aggregates over the booking set: booked minutes, revenue, average duration, bookings by day-of-week and hour, peak hour, top customers, and a day-by-day utilisation report with ASCII bars.
- **Loyalty tiers** — Bronze/Silver/Gold/Platinum tiers earned by cumulative confirmed bookings, each granting an advisory discount, plus a "bookings to next tier" progress view.
- **Availability search** — a read-only engine that finds open booking windows across a date range. Sweeps a per-day grid of candidate start times inside the business-hours window and reuses the validator's exact per-resource overlap query, so a slot it reports as free is one the create path will accept. Scan a single resource or all of them; bound by earliest-start/latest-end, grid step, weekday filters, and a minimum-free-capacity threshold; get earliest-first results (capped), the single next-available slot, or an overlap-collapsed list of distinct openings. Also renders a **date × hour heatmap**, a **coverage summary** (open-rate, busiest/quietest day), the **earliest opening per resource**, and **suggests the nearest alternatives** when a requested slot is full — the create flow surfaces these automatically on a capacity rejection.
- **Recurring availability** — the read-only counterpart to recurring series: check whether a fixed time-of-day stays bookable across N occurrences on a daily/weekly/biweekly/monthly/quarterly/annual cadence, reporting exactly which occurrences are open and which are blocked before you commit to creating the series.
- **Resource reassignment** — move a booking onto a different resource (or back to the default bucket) in place. The move is capacity-checked against the target resource at the booking's current slot and rejected if it would overflow, so a reassignment never creates an over-booking. Audit-logged like any other mutation.

## Snapshot, cancellation-policy & reviews menu

The CLI menu's final entries:

| # | Action | Description |
|---|--------|-------------|
| 27 | Save snapshot | Persist the whole system state to a JSON file |
| 28 | Load snapshot | Restore system state from a JSON file |
| 29 | Cancel with refund policy | Preview the fee/refund split, then cancel and refund |
| 30 | Manage customers | List/create/find/search/update/delete, CSV export, directory summary |
| 31 | Exit | Quit the CLI |

Loyalty tier/discount/progress data is now surfaced through the "Manage
customers" menu (list and directory-summary views). The Reminders and
Analytics subsystems described above remain library-level only — they
aren't currently wired into an interactive menu entry.

## Availability menu

The availability engine is driven from three CLI entries:

| # | Action | Description |
|---|--------|-------------|
| 29 | Find availability | Search open windows over a date range and (optionally) one resource. Renders a slot table or collapsed distinct openings, the next-available slot, an optional date × hour heatmap, a coverage summary, and the earliest opening per resource. Can filter out clashes for a named customer and probe the max bookable duration from a start time. |
| 30 | Reassign booking resource | Move a booking to a different resource (or the default bucket), capacity-checked against the target resource at the booking's current slot. |
| 31 | Check recurring availability | Test whether a fixed time-of-day stays open across N occurrences on a daily/weekly/biweekly/monthly/quarterly/annual cadence, reporting which occurrences are open and which are blocked. |

When a new booking is rejected purely on capacity, the create flow now lists the nearest open alternatives before offering the waitlist.

## Project Structure

```text
src/main/kotlin/com/booking/
├── config/
│   └── AppConfig.kt              # Central defaults: capacity, currency, file paths
├── model/
│   ├── Booking.kt                # Booking entity (status, time slot, attached quote, optional seriesId)
│   ├── Quote.kt                  # Persisted price-quote snapshot
│   ├── WaitlistEntry.kt          # Pending booking request held until capacity frees up
│   ├── PaymentIntent.kt          # Stripe-style payment intent (status state machine, amount frozen at create time)
│   ├── Customer.kt               # Customer directory record (name, contact, loyalty)
│   ├── Notification.kt           # Notification entity, channels, priority, status
│   ├── ReminderRule.kt           # Declarative offset-before-start reminder rule
│   └── AvailabilitySlot.kt       # Open booking window discovered by the availability engine
├── service/
│   ├── AuditLog.kt               # Immutable event log for all mutations
│   ├── BookingPricer.kt          # Pricing calculator that persists quotes back to bookings
│   ├── BookingService.kt         # Core CRUD, capacity, overlap, series queries
│   ├── BookingValidator.kt       # Composable validation rules (incl. capacity)
│   ├── RecurringBookingService.kt# Series creation (DAILY/WEEKLY/BIWEEKLY/MONTHLY) and bulk cancel
│   ├── ReportGenerator.kt        # Summary, schedule, and customer reports
│   ├── WaitlistService.kt        # FIFO queue with capacity-aware promotion
│   ├── PaymentProcessor.kt       # Pluggable gateway interface + MockPaymentProcessor for tests
│   ├── PaymentService.kt         # Intent lifecycle: create → confirm → (succeed | fail) → refund
│   ├── ICalExporter.kt           # RFC 5545 (.ics) renderer for single bookings or whole calendar
│   ├── NotificationService.kt    # Reminder bus: multi-channel dispatcher with priority flushing and stats
│   ├── ReminderScheduler.kt      # Materialises reminder rules into scheduled notifications
│   ├── AnalyticsEngine.kt        # Read-only aggregate metrics and utilisation
│   ├── LoyaltyEngine.kt          # Tier and discount computation from booking history
│   └── AvailabilityService.kt    # Read-only open-slot search over resources and date ranges
├── notification/
│   ├── NotificationEvent.kt      # Sealed hierarchy: BookingCreated/Cancelled, Payment*, WaitlistPromoted
│   ├── Notifier.kt               # Channel interface (name + handle)
│   ├── ConsoleNotifier.kt        # Stdout impl, tagged with [NOTIFY hh:mm:ss]
│   ├── EmailNotifier.kt          # Mock SMTP — writes to outbox.eml
│   ├── SmsNotifier.kt            # Mock SMS — writes truncated lines to sms.log
│   ├── NotificationPreferences.kt# Per-customer (channel, event-type) opt-outs
│   └── NotificationDispatcher.kt # Fanout with enable/disable toggle, prefs lookup, exception isolation
├── util/
│   ├── BookingFilter.kt          # Fluent sort/filter utility
│   └── TextTable.kt              # Fixed-width console table renderer
└── App.kt                        # CLI entry point
```
