# Product and technical design

## Product boundary

Local Ledger is a private awareness and budgeting tool for Indian bank-account
activity. It turns eligible SMS alerts into a local ledger, accepts optional
manual entries for cash or corrections, and exports user-directed reports.
Credit cards, statement reconciliation, and activity before setup remain outside
the current automated scope.

## Reliable, explainable ingestion

1. Android delivers `SMS_RECEIVED` to the manifest receiver.
2. Multipart segments are joined only in memory.
3. Carrier/circle prefix and message-category suffix are removed.
4. The normalized sender must match a selected bank. Frozen TRAI-derived headers
   and reviewed field-observed additions retain separate provenance.
5. Bound OTP, promotional, failed, future-debit and explicit credit-card messages
   are rejected in safety order. Merely mentioning "never share OTP" is not an
   OTP match.
6. Balance/limit clauses are masked before generic amount selection.
7. A deterministic parser extracts amount, direction, reference, date/time and a
   best-effort counterparty. A missing time uses the SMS timestamp.
8. A SHA-256 source key deduplicates receiver and inbox-scan paths; a bank,
   direction and reference index catches semantically duplicated alerts.
9. Parsed fields are committed to SQLite. The SMS body is discarded.
10. A privacy-safe diagnostic outcome records exactly where the pipeline stopped.

The parser deliberately uses small deterministic rules rather than an embedded
language model. This keeps the APK tiny, requires no network, produces
explainable failures, avoids model CPU/memory cost, and makes regression fixtures
reviewable. Community template support should arrive as invented/sanitized unit
tests followed by the narrowest reusable rule. The adversarial corpus is
grow-only: every false positive becomes a failing test before its fix.

## Dashboard module contract

`DashboardModules.all` is the ordered registry of stable module IDs. The user
can hide and reorder modules in Settings. The rendering branch in
`MainActivity.showDashboard` maps each ID to a native View:

- balance with tap-through bank split;
- inflow/outflow;
- daily spending pace;
- category composition;
- Sankey-style income-to-spending flow;
- pace-aware budget watch;
- derived insights;
- recent transactions.

A fork adds a graph by defining one stable `DashboardModuleSpec` and one
renderer branch. Storage and SMS ingestion do not need to change. Global period,
bank, direction, category, merchant and tag filters flow into the dashboard query
and therefore apply consistently across compatible modules.

No chart framework is included. Donut, trend and Sankey visuals are small custom
Canvas views that reuse paint objects and redraw only when data changes.

## Privacy display

- **Visible:** real values.
- **Hidden:** all formatted values become dots; graph proportions use substitute
  values.
- **Demo:** deterministic local transformations replace values, sensitive labels,
  dates and chart proportions. Empty views receive in-memory sample data, and
  safe preview dialogs demonstrate editing, manual entry, tags, budgets and
  diagnostics without reading real detail sheets or mutating the ledger.

Privacy display changes presentation only. Stored ledger values and exported
reports remain exact; export is unavailable until Visible mode is restored.

## Tags and classification

Categories remain the primary accounting classification. Tags are orthogonal and
many-to-many:

- a transaction tag affects only that payment;
- a merchant tag is inherited by every transaction for that merchant;
- a category tag is inherited by every transaction in that category.

Effective tags are the set union of all three levels. This supports cross-cutting
questions such as family, reimbursable, travel, work, shared, or tax-related
without duplicating categories.

## Manual-entry fields

Required:

- amount and debit/credit direction;
- selected bank account (the existing one-account-per-bank constraint remains).

Fast defaults and optional fields:

- current date/time, editable in a compact text field;
- merchant/description, defaulting to Cash expense or Manual income;
- category, tags and note;
- whether the entry changes the selected bank's calculated balance.

## Budget model

A budget targets one category, merchant, or tag and has a day, week, month, or
year period. The user chooses the amount and alert percentage. Each budget shows:

- actual spend and limit;
- elapsed-period fraction;
- straight-line projected spend;
- on-track, watch, projected-over, or over status.

Projection warnings begin only after 20% of the period and 20% of the budget have
been consumed. Notification alerts are claimed once per budget cycle, preventing
repeat noise; dashboard status remains continuously visible.

Budgeting guidance is intentionally non-prescriptive. The CFPB's spending tracker
and monthly budget approach—understand actual spending, total income and total
expenses before choosing changes—informs the design. A 50/30/20 split may be a
starting idea, never a default allocation.

Research sources:

- https://files.consumerfinance.gov/f/documents/cfpb_well-being_spending-tracker.pdf
- https://files.consumerfinance.gov/f/documents/cfpb_well-being_monthly-budget.pdf
- https://consumer.gov/your-money/making-budget

## Performance and storage

- Kotlin and Android framework Views; no Compose runtime.
- `SQLiteOpenHelper` with WAL and indexed time/bank/merchant/source keys; no ORM.
- One low-priority serialized executor for receiver and database work.
- No polling, persistent service, worker scheduler, remote sync or runtime
  registry download.
- Native `PdfDocument` and CSV streaming through `ACTION_CREATE_DOCUMENT`;
  no storage permission.
- Diagnostic retention: seven days and 120 rows.
- Release shrinking and resource shrinking enabled.

## Edge-to-edge layout

Android 15 enforces edge-to-edge for apps targeting SDK 35 or newer. The SDK 36
build's root View applies status,
navigation and display-cutout insets on every supported API level, keeping
headers and bottom navigation out of system controls while preserving the compact
single-activity shell.
