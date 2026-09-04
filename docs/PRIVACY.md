# Privacy and permissions

Local Ledger is intentionally offline.

## Android permissions

- `RECEIVE_SMS`: observe new transaction messages.
- `READ_SMS`: recover messages received after tracking began, including messages missed while the app was stopped.
- `android.hardware.telephony`: prevents installation on devices that cannot receive SMS.

The manifest does **not** declare `INTERNET`, contacts, location, phone, call-log, storage, notification, advertising-ID, or analytics permissions.

## Stored data

The local SQLite database stores selected banks, opening balances, parsed transaction fields, merchant rules, categories, and budgets. It stores a SHA-256 fingerprint for deduplication but does not copy the complete SMS body.

Android cloud backup and device-to-device transfer are disabled for the database and settings.

## Processing boundary

Messages are considered only when their normalized sender header is in the bundled TRAI-derived registry and belongs to a bank selected during setup. Non-bank messages are ignored before their content is parsed.

No network client or third-party SDK is included.
