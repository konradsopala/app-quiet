# Changelog

All notable changes to the Booking System are documented in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/),
and this project does not yet follow semantic versioning.

## [Unreleased]

### Added

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
