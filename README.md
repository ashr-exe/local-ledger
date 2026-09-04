# Local Ledger

[![Android CI](https://github.com/ashr-exe/local-ledger/actions/workflows/android-ci.yml/badge.svg)](https://github.com/ashr-exe/local-ledger/actions/workflows/android-ci.yml)
[![CodeQL](https://github.com/ashr-exe/local-ledger/actions/workflows/codeql.yml/badge.svg)](https://github.com/ashr-exe/local-ledger/actions/workflows/codeql.yml)
[![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](docs/BUILD.md)
[![Offline](https://img.shields.io/badge/network_permission-none-5C6BC0)](docs/PRIVACY.md)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange)](docs/LIMITATIONS.md)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

An offline-first Android personal-finance tracker that turns verified Indian bank
SMS alerts into a useful local ledger—without an account, a cloud backend,
analytics, or internet permission.

> [!IMPORTANT]
> Local Ledger is an early alpha and a convenience tool, not a bank statement or
> an accounting system. SMS delivery and parsing are inherently fallible. Always
> reconcile important decisions against your bank's official records.

## Why it is different

- **Private by construction:** the APK does not request Android's `INTERNET`
  permission. Processing and storage stay on the device.
- **Almost no setup:** select one account per bank, enter its current balance,
  grant SMS access, and start tracking.
- **Event-driven and lightweight:** no polling, foreground service, worker
  scheduler, analytics SDK, ad SDK, ORM, or dependency-injection framework.
- **Defensive ingestion:** a sender must match the bundled bank-specific registry
  before a message is parsed. Duplicate delivery and inbox scanning share one
  fingerprint-based deduplication path.
- **Useful personalisation:** give merchants nicknames, create custom categories,
  set monthly category budgets, and view a compact dashboard.

## What it tracks

Local Ledger currently targets successful digital transactions affecting selected
bank accounts: debits, credits, transfers, reversals, refunds, and similar balance
movements described by supported SMS templates. It extracts the amount,
credit/debit direction, transaction time when present, and a best-effort merchant
label. Full SMS bodies are not retained after parsing.

The bundled static registry currently contains **46 banks and 562 normalized
sender headers**, derived from TRAI's compiled SMS-header workbook dated
16 June 2020. Carrier/circle routing prefixes and message-category suffixes are
normalized before matching.

## Install

### From a GitHub release

1. Open the repository's **Releases** page and download the APK and its
   `SHA256SUMS` file.
2. Verify the checksum, then transfer the APK to an Android 8.0+ phone.
3. Allow installation from the chosen file manager and install the APK.
4. Select your banks, enter each current account balance, and tap **Start
   tracking**.
5. Grant `RECEIVE_SMS` and `READ_SMS` when Android asks.

Until the first signed release exists, build from source using
[the build guide](docs/BUILD.md). Do not install APKs attached by unknown third
parties.

## Everyday use

- New matching bank alerts are processed automatically.
- Open the app to rescan messages received since tracking began.
- Assign a nickname and category to a merchant; the rule applies consistently to
  matching transactions.
- Add monthly category caps and use the dashboard to compare spending with the
  chosen plan.

The app presents 50/30/20 only as an optional starting framework. Budgeting is
personal, so categories and caps remain user-defined.

## Permissions and data boundary

| Permission | Why it is needed |
|---|---|
| `RECEIVE_SMS` | Process new bank alerts when Android delivers them. |
| `READ_SMS` | Recover eligible messages received after tracking began while the app was stopped. |

There is deliberately no internet, contacts, location, phone, call-log, storage,
notification, advertising-ID, or analytics permission. Android backup and
device-to-device transfer are disabled for ledger data. See the full
[privacy model](docs/PRIVACY.md).

## Known limits

The dashboard cannot be 100% authoritative. Banks and carriers may omit, delay,
duplicate, truncate, or change an alert; some transactions are statement-only;
and a parser may misinterpret an unfamiliar template. Cash, credit cards, and
activity before setup are outside the current scope. Read the complete
[limitations](docs/LIMITATIONS.md) before relying on totals.

## Project documentation

- [Build and install](docs/BUILD.md)
- [Architecture and design](docs/DESIGN.md)
- [Privacy and permissions](docs/PRIVACY.md)
- [Limitations](docs/LIMITATIONS.md)
- [Roadmap](docs/ROADMAP.md)
- [Release process](docs/RELEASING.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## Development status

Version `0.1.0` is an alpha foundation. Unit tests, Android lint, release builds,
dependency updates, and CodeQL analysis are automated through GitHub Actions.
See [CHANGELOG.md](CHANGELOG.md) for shipped changes and
[the roadmap](docs/ROADMAP.md) for planned work.

## Sender-registry provenance

The checked-in registry is reproducible using
[`tools/extract_trai_registry.py`](tools/extract_trai_registry.py). Its current
source is TRAI's [compiled header workbook published 16 June 2020](https://www.trai.gov.in/node/7411).
The source date is intentionally prominent because the registry can become stale;
updating it without adding a runtime network dependency is a roadmap item.

## License

Licensed under the [Apache License 2.0](LICENSE).
