# Changelog

Notable changes are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases use
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned

- Broader sanitized SMS-template coverage and reconciliation tools.
- Export, encrypted backup, and multi-account-per-bank support.

## [0.1.0] - 2026-09-05

### Added

- Offline Android ledger with no internet permission.
- One-account-per-bank setup with opening balances.
- Manifest SMS receiver plus catch-up inbox scan.
- TRAI-derived static registry covering 46 banks and 562 normalized headers.
- Best-effort debit, credit, amount, timestamp, and merchant extraction.
- SHA-256 event deduplication without storing full SMS bodies.
- Merchant nicknames, custom categories, monthly budgets, and dashboard views.
- Unit tests and Android lint/build automation.

[Unreleased]: https://github.com/ashr-exe/local-ledger/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/ashr-exe/local-ledger/releases/tag/v0.1.0
