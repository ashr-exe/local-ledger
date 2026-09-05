# Roadmap

The roadmap prioritizes correctness and user control before adding breadth. It is
directional, not a delivery commitment.

## Reliability foundation

- Expand the sanitized parser corpus across banks, rails, reversals, fees, and
  ambiguous templates.
- Add an opt-in, locally redacted review flow for unfamiliar templates without
  retaining complete SMS content.
- Add deletion, split transactions, opening-balance edits, and explicit
  reconciliation adjustments.
- Add statement import and reconciliation with explicit discrepancy reporting.
- Automate registry provenance checks and periodic offline snapshot releases.

## Account and budgeting depth

- Support multiple accounts per bank and credit cards.
- Add transfer pairing while retaining both underlying account events.
- Add recurring expenses, sinking funds, rollover budgets, and goals.
- Add module sizing and module-specific display settings while retaining the
  native lightweight dashboard contract.

## Portability without surveillance

- Add explicit encrypted archive export/import using a user-held passphrase.
- Add CSV import with a deliberate mapping and duplicate-review flow.
- Evaluate opt-in device-to-device synchronization that remains end-to-end
  encrypted; no sync service will be mandatory.

## Distribution quality

- Reproducible signed releases with published checksums and provenance.
- Instrumented tests across supported Android API levels and OEM behavior.
- Accessibility review, translations, and performance budgets for low-end devices.
- Evaluate F-Droid distribution after the sender-registry update process is fully
  reproducible and licensing is settled.
