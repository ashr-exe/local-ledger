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
  legitimate newer or changed headers may be absent.
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
- Bank accounts only; explicit credit-card alerts are ignored.
- No statement import or reconciliation.
- No editing, split transactions, recurring-transaction model, or exchange-rate
  handling.
- No encrypted export, backup, or cross-device synchronization.
- No guarantee that every Indian bank or regional institution is represented.
- The dashboard is informational and is not financial, tax, accounting, or legal
  advice.

## Safe use

Use Local Ledger for awareness and budgeting. Reconcile against official bank
statements before paying bills, filing taxes, disputing transactions, or making
decisions where an incorrect balance could cause harm.
