# Roadmap

The roadmap prioritizes correctness and user control before adding breadth. It is
directional, not a delivery commitment.

## Reliability foundation

- Expand the sanitized parser corpus across banks, rails, reversals, fees, and
  ambiguous templates.
- Add an in-app review queue for low-confidence and rejected bank messages without
  retaining unnecessary content.
- Add manual correction, deletion, split transactions, and opening-balance edits.
- Add statement import and reconciliation with explicit discrepancy reporting.
- Version the sender registry and publish provenance/checksums for every update.

## Account and budgeting depth

- Support multiple accounts per bank and credit cards.
- Add transfer pairing while retaining both underlying account events.
- Add recurring expenses, sinking funds, rollover budgets, goals, and configurable
  budget periods.
- Add richer, accessible dashboards and user-selectable widgets without a heavy UI
  runtime.

## Portability without surveillance

- Add explicit encrypted export/import using a user-held passphrase.
- Add local CSV/JSON export with clear privacy warnings.
- Evaluate opt-in device-to-device synchronization that remains end-to-end
  encrypted; no sync service will be mandatory.

## Distribution quality

- Reproducible signed releases with published checksums and provenance.
- Instrumented tests across supported Android API levels and OEM behavior.
- Accessibility review, translations, and performance budgets for low-end devices.
- Evaluate F-Droid distribution after the sender-registry update process is fully
  reproducible and licensing is settled.
