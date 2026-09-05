# Limitations and reliability

Local Ledger cannot guarantee a complete or perfectly accurate ledger. Its totals
are an interpretation of messages available to one Android device, not a bank's
authoritative account record.

## Missing events

- An issuing bank may not send an alert for every event or may apply thresholds,
  channel-specific behavior, account preferences, or operational exceptions.
- Carrier filtering, congestion, roaming, device storage, SIM changes, OEM power
  management, and Android permission state can delay or prevent delivery.
- Some fees, interest, holds, settlement adjustments, standing instructions, and
  corrections may appear only on the account statement.
- Messages received before tracking starts are intentionally excluded.
- Cash and other activity without a bank-account SMS trail are outside scope.

## Parsing errors

- Banks can change message wording without notice.
- Amounts, dates, references, and merchant strings are not standardized across
  institutions or payment rails.
- A previously unseen template can be rejected, classified incorrectly, or yield
  an incomplete merchant label.
- The sender registry is static and currently derived from a 2020 TRAI workbook;
  legitimate newer or changed headers may be absent. Reviewed field-observed
  additions are stored separately from the official source list.
- Sender matching reduces false positives but is not cryptographic proof of
  origin. SMS sender presentation depends on the telecom ecosystem and device.

## Balance behavior

The user-provided opening balance is advanced by parsed credits and debits.
Delivery or parsing errors therefore cause drift. Transfers between two selected
accounts should net to zero in aggregate, but one side can arrive late or fail to
parse, and per-account balances can still be wrong. Refunds and reversals are
recorded as their own events when alerts arrive.

## Current product scope

- One account per bank.
- Bank accounts only; explicit credit-card alerts are ignored. Credit-card
  tracking is planned, but not supported in this release.
- No statement import or reconciliation.
- Manual additions, merchant/category rules, and tags are supported, but deletion,
  split transactions, recurring-transaction automation, and exchange-rate
  handling are not.
- CSV/PDF export exists, but there is no report import, encrypted archive,
  automatic backup, or cross-device synchronization.
- No guarantee that every Indian bank or regional institution is represented.
- The dashboard is informational and is not financial, tax, accounting, or legal
  advice.

## Safe use

Use Local Ledger for awareness and budgeting. Reconcile against official bank
statements before paying bills, filing taxes, disputing transactions, or making
decisions where an incorrect balance could cause harm.

## Sideloading

Android or an OEM may block an unverified sideloaded app from receiving sensitive
SMS access until the user explicitly allows restricted settings. Play Protect may
also warn or block installation. These platform controls are outside the app.
Install only a checksum-verified signed release and re-enable any temporarily
paused device protection immediately.
