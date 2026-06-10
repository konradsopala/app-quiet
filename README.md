# Booking System

A command-line booking system built with Kotlin. Supports full booking lifecycle management with validation, audit logging, reporting, advanced search, time-slot scheduling with configurable capacity, persistent price quotes, recurring booking series, a capacity-aware FIFO waitlist, payment intents (Stripe-style, with a pluggable processor), and iCalendar (`.ics`) export.

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

## Project Structure

```text
src/main/kotlin/com/booking/
├── model/
│   ├── Booking.kt                # Booking entity (status, time slot, attached quote, optional seriesId)
│   ├── Quote.kt                  # Persisted price-quote snapshot
│   ├── WaitlistEntry.kt          # Pending booking request held until capacity frees up
│   └── PaymentIntent.kt          # Stripe-style payment intent (status state machine, amount frozen at create time)
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
│   └── ICalExporter.kt           # RFC 5545 (.ics) renderer for single bookings or whole calendar
├── util/
│   └── BookingFilter.kt          # Fluent sort/filter utility
└── App.kt                        # CLI entry point
```
