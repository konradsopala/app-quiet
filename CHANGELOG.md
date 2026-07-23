# Changelog

All notable changes to the Booking System are documented in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/),
and this project does not yet follow semantic versioning.

## [Unreleased]

### Added

- **Statistics**
  - Cancellation rate metric: `StatisticsService.cancellationRate()` reports
    the percentage of all bookings (confirmed + cancelled) that were
    cancelled. Shown in the Statistics menu alongside the other activity
    metrics.

- **Cancellation & refund policy**
  - `CancellationPolicy` model: notice-based refund tiers (default free ≥48h,
    50% ≥24h, 25% ≥2h) plus a no-show percent, with validation and a
    most-generous-match lookup.
  - `CancellationService`: previews the fee/refund split for a booking and
    performs a policy-based cancellation that returns the refundable share and
    retains the fee. Uses the actual settled payments as the refund basis (or
    the quote total, advisory, when unpaid).
  - Partial refunds: `PaymentIntent.refundedAmount` / `remainingRefundable`,
    `PaymentService.refundPartial` and `refundAmountForBooking`, with
    `netSettled` now reflecting a retained fee. Round-tripped through snapshots
    (backward-compatible: absent field decodes to 0).
  - Loyalty grace bonus: customers with at least three years of tenure get an
    extra refund percentage on top of their notice tier, capped at 100% so the
    combined refund can never exceed the charged amount. The audit entry for a
    cancellation references the customer by id, not raw contact details.
  - CLI menu option 29 "Cancel with refund policy" — previews the split and
    asks for confirmation before cancelling.

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

- **Customer directory management**
  - New CLI menu option 30, "Manage customers", exposes the existing
    `CustomerService` CRUD through an interactive submenu: list (contact
    info, loyalty years, tier, and confirmed-booking count), create, find
    (by id or exact name), search (by name substring), update
    (blank-to-keep semantics per field), delete (with confirmation), CSV
    export, and a directory summary (tier distribution, dormant
    zero-confirmed-booking count, and a top-customers-by-confirmed-bookings
    table).
  - `CustomerService.exportToCsv()`, mirroring `BookingService.exportToCsv`'s
    quoting convention.
  - `AppConfig.defaultCustomersCsvPath` (default `customers.csv`), matching
    the existing default CSV/ICS path conventions.

- **Utilities**
  - `TextTable`: a dependency-free, auto-sizing fixed-width console table
    renderer with per-column alignment, used by the analytics menu.

- **CLI**
  - Menu now runs through option 34 (Exit); options 27–29 are snapshot
    save/load and the refund-policy cancellation, 30 is loyalty status, and
    31–33 are the new review actions. The reminders and analytics subsystems
    above are library-level only — they are not yet wired into the
    interactive menu.
  - The main menu banner now reflects the expanded feature set.

- **Continuous integration**
  - GitHub Actions workflow (`.github/workflows/ci.yml`) that sets up JDK 17 and
    the Kotlin compiler, builds a runnable jar from all sources, and uploads it
    as a build artifact on every push to `main` and every pull request.

### Fixed

- `StatisticsService.cancellationRate()` referenced a non-existent `staus`
  property instead of `status`, which failed to compile.
