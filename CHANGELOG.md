# Changelog

All notable changes to the Booking System are documented in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/),
and this project does not yet follow semantic versioning.

## [Unreleased]

### Added

- **Availability search engine**
  - `AvailabilitySlot` model: an immutable value object describing an open
    window (resource, date, start/end, and free-vs-total capacity).
  - `AvailabilityService`: a read-only engine that sweeps a date range and a
    per-day grid of candidate start times, reusing the validator's exact
    per-resource overlap query so a reported slot is one the create path will
    accept. Supports single/all-resource scans, business-hours bounds, a
    configurable grid step, weekday filters, a minimum-free-capacity threshold,
    an earliest-first result cap, `findNextAvailable`, an overlap-collapsing
    view, a per-day open-count map, `coverageSummary` (open-rate + busiest/
    quietest day), `renderHeatmap` (date × hour grid), `firstFitPerResource`
    (earliest opening per resource), `suggestAlternatives` (nearest openings to
    a full slot), and `findRecurringSlots` (whether a fixed time stays open
    across a `RecurringBookingService.Cadence`).
  - `BookingService.reassignResource`: move a booking to another resource (or
    the default bucket), capacity-checked against the target's current slot and
    audit-logged.
  - CLI menu options 29 "Find availability" (slot table / distinct openings,
    next-available, optional heatmap, coverage, per-resource comparison),
    30 "Reassign booking resource", and 31 "Check recurring availability". The
    create flow now suggests nearest alternatives on a capacity rejection.

- **Reminders subsystem**
  - `ReminderRule` model: declarative, offset-before-start reminder definitions
    with a channel, priority, and a `{token}` message template. Ships with a
    default rule set (day-before email, two-hour SMS).
  - `ReminderScheduler` service: materialises rules into scheduled
    notifications for a booking, skipping any whose fire time is already in the
    past and reporting the count. Supports add/remove rule and full reschedule.
  - New bookings automatically schedule their reminders on creation.

- **Notifications (reminder bus)**
  - `Notification` model with channels (Email, SMS, Push, Console), priority
    buckets (Low/Normal/High/Urgent), and a delivery lifecycle
    (Pending → Sent / Failed / Cancelled).
  - Per-channel length limits with automatic body truncation.
  - `NotificationService`: an in-memory, synchronous dispatcher with pluggable
    per-channel sinks, priority-ordered flushing of due notifications,
    per-booking cancellation, delivery statistics, and full history. This is a
    queue-oriented bus that complements (rather than replaces) the existing
    event-fanout `NotificationDispatcher`.

- **Analytics subsystem**
  - `AnalyticsEngine`: read-only aggregates over the booking set — total booked
    minutes, revenue, average duration, bookings by day-of-week and by hour,
    peak hour, and a top-customers leaderboard.
  - Day-by-day utilisation report rendered with ASCII bars.
  - A compact textual digest suitable for console output or a notification body.

- **Loyalty subsystem**
  - `LoyaltyEngine` with Bronze/Silver/Gold/Platinum tiers earned by cumulative
    confirmed bookings, each granting an advisory discount.
  - Progress view ("N bookings to GOLD") and discount application helper.

- **Utilities**
  - `TextTable`: a dependency-free, auto-sizing fixed-width console table
    renderer with per-column alignment, used by the analytics menu.

- **CLI**
  - Five new menu entries (27–31) for scheduling reminders, flushing due
    reminders, the analytics digest, the utilisation report, and loyalty status.
  - The main menu banner now reflects the expanded feature set.

- **Continuous integration**
  - GitHub Actions workflow (`.github/workflows/ci.yml`) that sets up JDK 17 and
    the Kotlin compiler, builds a runnable jar from all sources, and uploads it
    as a build artifact on every push to `main` and every pull request.
