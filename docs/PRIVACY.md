# Privacy and permissions

Local Ledger is intentionally offline and stores data only on the device.

## Android permissions

- `RECEIVE_SMS`: observe new transaction messages.
- `READ_SMS`: recover eligible messages received after tracking began.
- `POST_NOTIFICATIONS`: optional; requested only after a budget is created, for
  at most one attention alert per budget period.
- `android.hardware.telephony`: prevents installation on devices that cannot
  receive SMS.

The manifest explicitly removes `INTERNET`, network-state, network-change, and
Wi-Fi-state permissions during manifest merging. The production dependency graph
contains no network client, WebView integration, analytics, ads, login, or remote
configuration. CI verifies the source contract, and the release workflow inspects
the permissions of the final signed APK.

Without Android's `INTERNET` permission, the installed APK cannot open internet or
local-network sockets. A person who deliberately edits the source and removes
these controls is building a different application; no open-source project can
prevent a hostile fork from changing its own code.

## Stored data

SQLite stores selected banks, opening balances, parsed transaction fields,
optional payment references and notes, merchant rules, categories, tags,
budgets, and deduplication fingerprints. Complete SMS bodies are never stored.

The rotating diagnostic log stores only:

- timestamp and pipeline outcome;
- normalized sender header and matched bank identifier when available;
- boolean parser signals such as whether an amount or direction was detected;
- message character count and whether SMS time had to be used.

It never stores message text, amounts, balances, account digits, UPI references,
phone numbers, or merchant/person names. It retains at most 120 events and
deletes events older than seven days.

Android cloud backup and device-to-device transfer are disabled for the database
and settings. CSV and PDF reports are created only after the user chooses a
destination through Android's document picker. Those exported files are outside
the app sandbox and become the user's responsibility.

## Privacy display

Hidden mode obscures values and sensitive labels. Demo mode replaces values,
accounts, merchants, categories, tags, dates, graphs and drill-down content with
deterministic synthetic presentation. When real views are empty, in-memory sample
data keeps the product explorable; it is never inserted into the ledger. Real edit
sheets cannot be opened and real reports cannot be exported until Visible mode is
restored.

## Processing boundary

The carrier/circle prefix and service suffix are normalized first. A message is
considered only when the remaining header matches the selected bank through
either the frozen TRAI-derived registry or a separately marked reviewed
field-observed correction. Promotional, OTP, failed and explicit credit-card
messages are rejected before insertion.

No SMS content, diagnostic report, report export, or ledger field is transmitted
by Local Ledger.
