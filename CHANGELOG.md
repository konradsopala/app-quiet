# Changelog

All notable changes to the Booking System are documented in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/),
and this project does not yet follow semantic versioning.

## [Unreleased]

### Added

- **Operator access control**
  - `Operator` model (username, display name, CASHIER / MANAGER / ADMIN
    role, PBKDF2 PIN hash + salt, lockout counters, must-change-PIN flag).
  - `security.Permission` + `AccessPolicy`: cumulative role → permission
    matrix covering every gift-card action, operator management and
    snapshot load.
  - `security.PinHasher`: PBKDF2-HMAC-SHA256 (120k iterations, 16-byte
    salt), constant-time verify, weak-PIN rejection.
  - `OperatorService`: sign-in with per-account lockout after
    `maxFailedSignIns`, uniform failure messages, `require(permission)`
    gate that audit-logs refusals, second-operator override for high-value
    voids, register / change PIN / reset PIN / unlock / deactivate, and a
    config-driven bootstrap ADMIN created on first run.
  - CLI options 47–50 (sign in/out, register, manage, change PIN); Exit
    moves to 51. Gift-card options 39–46 and Load snapshot now require the
    matching permission; voiding above `giftCardVoidApprovalThreshold`
    prompts for a manager's approval.
  - Gift-card audit entries now record the acting operator instead of
    `SYSTEM` when someone is signed in.
  - Operators round-trip through snapshots; older snapshots load with none.
  - New audit actions: `OPERATOR_REGISTERED`, `OPERATOR_SIGNED_IN`,
    `OPERATOR_SIGN_IN_FAILED`, `OPERATOR_LOCKED`, `OPERATOR_SIGNED_OUT`,
    `OPERATOR_PIN_CHANGED`, `OPERATOR_DEACTIVATED`, `OPERATOR_OVERRIDE`,
    `ACCESS_DENIED`.
- **Outbound webhook**
  - `WebhookNotifier`: HTTPS-only (loopback may use http) POST of every
    `NotificationEvent` as JSON with `X-Booking-Timestamp` and an
    HMAC-SHA256 `X-Booking-Signature`; receiver-side `verify()` with a
    replay window. Registered only when `webhookUrl` is configured and
    disabled until enabled from the channel menu.
  - `NotificationEvent.GiftCardActivity`: emitted for every gift-card
    ledger entry via the new `GiftCardService.onTransaction` hook.
  - `AppConfig` gains `maxFailedSignIns`, `signInLockoutMinutes`,
    `giftCardVoidApprovalThreshold`, `bootstrapAdminUsername`,
    `bootstrapAdminPin`, `webhookUrl`, `webhookSecret`,
    `webhookTimeoutMillis`.
- **Gift cards subsystem**
  - `GiftCard` model: a prepaid stored-value card with a customer-facing
    `GC-XXXX-XXXX-XXXX` code, frozen `initialValue`, moving `balance`,
    optional purchaser link / recipient / message, and an
    `ACTIVE → DEPLETED → ACTIVE` / `EXPIRED` / `VOIDED` lifecycle.
  - `GiftCardTransaction` model: append-only ledger entry (ISSUED,
    RELOADED, REDEEMED, REVERSED, VOIDED, EXPIRED) carrying the balance
    after it applied, so statements render without replaying history.
  - `GiftCardCodeGenerator`: 32-symbol look-alike-free alphabet with a
    Luhn mod-N check character; forgiving normalisation of operator input
    (case, dashes, `O`/`0`, `I`/`L`/`1`, `Z`/`2`).
  - `GiftCardService`: issue, reload (with a per-card exposure cap),
    redeem against a quoted `CONFIRMED` booking (capped at both the card
    balance and the booking's remaining balance), reverse a redemption or
    all redemptions for a booking, void with a mandatory reason, and a lazy
    expiry sweep that forfeits lapsed balances. Accounting views:
    outstanding liability, total sold, and breakage per currency.
  - `GiftCardStatementRenderer`: per-card statement, card table with an
    expiring-soon marker, and a liability report with a 30-day expiry list.
  - CLI menu options 39–46 (Exit moves to 47). Cancelling a booking, with
    or without the refund policy, now returns redeemed gift-card value to
    the cards it came from.
  - `AppConfig` gains `giftCardExpiryMonths`, `minGiftCardValue`,
    `maxGiftCardValue`, and default CSV paths for cards and the ledger.
  - Cards and their ledger round-trip through snapshots; older snapshots
    without the sections load with none.
  - New audit actions: `GIFT_CARD_ISSUED`, `GIFT_CARD_RELOADED`,
    `GIFT_CARD_REDEEMED`, `GIFT_CARD_REVERSED`, `GIFT_CARD_VOIDED`,
    `GIFT_CARD_EXPIRED`.

- **Reviews subsystem**
  - `Review` model: a 1–5 star rating plus an optional (500-char max)
    comment, tied to a single booking.
  - `ReviewService`: enforces that a review can only be added to a
    `CONFIRMED` booking whose date has already passed, and that each
    booking carries at most one review. Provides rating aggregates —
    overall/per-customer average, star distribution, and a low-rated
    (1–2 star) follow-up queue — plus a one-line summary digest.
  - CLI menu options 31–34: add a review, look up a customer's reviews
    (case-insensitive partial match) with their average rating, print
    the system-wide review summary, and export all reviews to CSV.
  - Reviews round-trip through snapshots (`SnapshotStore`); older
    snapshots without a `reviews` section load with none, so the change
    is backward-compatible.
  - Every review is audit-logged (`REVIEW_ADDED`).

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
  - Menu now runs through option 35 (Exit); options 27–29 are snapshot
    save/load and the refund-policy cancellation, 30 is loyalty status, and
    31–34 are the new review actions. The reminders and analytics subsystems
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
