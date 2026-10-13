# Memberships

This document describes how the membership subsystem (`com.booking.membership`)
behaves. It complements the feature summary in the README.

## Concepts

| Type | Purpose |
| --- | --- |
| `MembershipPlan` | A purchasable tier: price, interval, trial, setup fee, included minutes, rollover cap, booking discount, seat limit. |
| `Membership` | One customer's subscription to a plan, with period boundaries and a guarded status. |
| `MembershipCharge` | A signed amount owed or credited. Append-only; retries add attempts, refunds add `CREDIT` charges. |
| `BenefitLedger` | Append-only booking-minute entries; the balance is their sum. |
| `RenewalScheduler` | The only component that moves memberships through time. |

All amounts are whole cents (`Long`). Convert at the edges with `Cents.parse`
and `Cents.format`.

## Lifecycle

```
TRIALING ──► ACTIVE ◄──► PAST_DUE ──► SUSPENDED ──► EXPIRED
   │           │  ▲          │
   │           ▼  │          │
   │         PAUSED          │
   └──────────► CANCELLED ◄──┘
```

* `EXPIRED` and `CANCELLED` are terminal; `Membership.transitionTo` rejects any
  move out of them.
* Only `TRIALING`, `ACTIVE` and `PAST_DUE` members *hold benefits* (discount and
  minutes). Paused and suspended members keep their balance but cannot spend it.

## Billing periods

Periods are anchored to the start date, not chained from the previous period.
A monthly member who starts on 31 January is billed on 28 February, 31 March,
30 April and so on — never drifting to the 28th.

## Plan changes

* **Immediate change** — `changePlan` credits the unused fraction of the old
  price and charges the same fraction of the new price, using the real length of
  the current period. A positive net is charged through the gateway at once; a
  negative net is recorded as a `CREDIT`. Upgrades that raise the allowance get a
  prorated top-up of benefit minutes.
* **Scheduled change** — `scheduleDowngrade` sets a pending plan applied by the
  scheduler at the next renewal, with no proration.

## Cancellation

* `atPeriodEnd = true` keeps the member live; the scheduler finalises it when the
  period ends. It can be withdrawn with `undoScheduledCancel`.
* `atPeriodEnd = false` ends it now. With `refundUnused` the unused fraction of
  the latest paid recurring charge is refunded through the gateway.
* Finalising a cancellation voids open charges and forfeits leftover minutes.

## Dunning

`DunningPolicy.DEFAULT` retries a failed renewal 1, 3 and 7 days after the due
date. The member is `PAST_DUE` while retries remain, `SUSPENDED` once they are
exhausted (or immediately on a non-retryable decline such as an expired card),
and `EXPIRED` after a further 14 grace days. A successful retry clears the
arrears and restores `ACTIVE`.

## Benefit minutes

* `GRANT` at the start of each period; `CONSUME` / `REFUND` per booking.
* At each renewal `closePeriod` writes an `EXPIRE` for the whole leftover and a
  `ROLLOVER` for the part within the plan's cap, so history shows exactly what
  was forfeited and what carried over.
* Consumption is idempotent per booking id and never partially applied.

## CSV import

```
customer_id,plan,start_date[,status][,cancel_reason]
```

`status` is `ACTIVE` (default), `PAUSED` or `CANCELLED`. Imports are validated
in full first: if any row is bad, nothing is written. Imported members do not
touch the payment gateway. A sample lives in `samples/memberships_import.csv`
(create the `Flex`, `Studio` and `Annual Elite` plans first — the demo data does
this for you).

## Trying it

1. Run the app and choose **47) Memberships**.
2. Option **18** loads demo data; **17** runs the built-in self-check.
3. Option **10** advances the simulated clock and optionally runs renewals — use
   it to watch trials convert and `demo-10`'s declined card walk through dunning.

## Known limitations

* State is in memory only; memberships are not part of the snapshot store yet.
* The gateway is a mock; a real integration must honour the idempotency key.
