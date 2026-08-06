# Changelog

All notable changes to the Booking System are documented in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/),
and this project does not yet follow semantic versioning.

## [Unreleased]

### Added

- **Staff scheduling subsystem**
  - `Staff` model: name, role, contact info, a skills set, and an active
    flag (deactivated staff can no longer be assigned to new bookings).
  - `Shift` model: a single staff member's availability window on a
    given date.
  - `StaffService`: staff CRUD, single/weekly shift scheduling (weekly
    batches skip and report any date that would overlap an existing
    shift rather than aborting), and the availability check consulted
    before an assignment is accepted — a staff member must have a shift
    covering the requested window *and* not already be booked over it.
    Also computes per-staff workload (confirmed bookings, booked
    minutes).
  - `Booking.staffId`: optional link to a `Staff` member, mirroring the
    existing `resourceId` pattern. Threaded through `BookingService`
    creation/update, `BookingValidator` (availability is re-checked on
    both create and update), and snapshot persistence.
  - `StatisticsService.staffUtilisation`: booked-vs-scheduled-shift-minutes
    percentage per staff member.
  - `ReportGenerator.generateStaffSchedule`: a day's shifts and the
    bookings assigned within each; the summary report also gets a Staff
    Workload section when a `StaffService` is wired in.
  - `ICalExporter` emits an `ATTENDEE` line for the booking's assigned
    staff member when a `StaffService` is wired in (falls back to the
    existing no-reply placeholder email when the staff member has none
    on file, same as `ORGANIZER`).
  - CLI menu options 31–38: register/deactivate/reactivate staff, list
    staff, add a single shift or a weekly batch, view a day's staff
    schedule (also reachable via the report menu's new "d) Staff
    schedule" option), view workload/utilisation, and export the staff
    directory to CSV. Booking creation and update now prompt for a
    staff assignment, and advanced search gained a staff-id filter.
  - Staff and shifts round-trip through `SnapshotStore` (backward
    -compatible: snapshots written before this change load with none).
  - Every registration, deactivation, and shift change is audit-logged.
  - Fixed in passing: `createBooking()`'s CLI handler computed and
    validated a `resourceId` but never actually passed it to
    `BookingService.createBooking`, so the resource assignment silently
    never took effect; this is now wired through alongside the new
    `staffId`.

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

- **Utilities**
  - `TextTable`: a dependency-free, auto-sizing fixed-width console table
    renderer with per-column alignment, used by the analytics menu.

- **CLI**
  - Menu now runs through option 39 (Exit); options 27–29 are snapshot
    save/load and the refund-policy cancellation, 30 is loyalty status, and
    31–38 are the new staff-scheduling actions. The reminders and analytics
    subsystems above are library-level only — they are not yet wired into
    the interactive menu.
  - The main menu banner now reflects the expanded feature set.

- **Continuous integration**
  - GitHub Actions workflow (`.github/workflows/ci.yml`) that sets up JDK 17 and
    the Kotlin compiler, builds a runnable jar from all sources, and uploads it
    as a build artifact on every push to `main` and every pull request.
