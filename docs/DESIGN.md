# Product and technical design

## Product boundary

Local Ledger tracks successful online bank-account payments from verified SMS alerts after the user establishes an opening balance. Cash, offline payments, statement-only adjustments, and credit cards are outside version 0.1.

## Event path

1. Android delivers `SMS_RECEIVED` to a manifest receiver.
2. Multipart segments are joined in memory.
3. The routing prefix (`XY-`) and category suffix (`-T`, `-S`, `-P`, `-G`) are removed.
4. The remaining sender header must match the selected bank in the bundled registry.
5. Failed, promotional, OTP, due-date, and explicit credit-card messages are rejected.
6. Amount, debit/credit direction, embedded date/time when present, and merchant text are extracted.
7. A SHA-256 key over sender, normalized body, and SMS timestamp deduplicates receiver and inbox-scan paths.
8. Only parsed fields and the fingerprint are committed to SQLite.

## Budget model

Budgets are monthly category caps. Merchant rules apply a nickname and category to all matching transactions. The UI presents the 50/30/20 rule only as an optional guide, not a universal prescription: 50% of take-home income for needs, 30% for wants, and 20% for savings/debt. Users create their own categories and limits.

Research sources:

- https://www.consumerfinance.gov/consumer-tools/educator-tools/youth-financial-education/teach/activities/analyzing-budgets/
- https://files.consumerfinance.gov/f/documents/cfpb_worksheet_my-spending-rule-to-live-by.pdf
- https://consumer.gov/your-money/making-budget

## Performance choices

- Kotlin with Android framework views; no Compose runtime.
- SQLiteOpenHelper; no ORM or dependency-injection framework.
- One serialized background executor for SMS/database work.
- Indexed time, bank, merchant, and fingerprint fields.
- A custom Canvas chart with reused drawing objects.
- No polling, foreground service, worker scheduler, or persistent process.
