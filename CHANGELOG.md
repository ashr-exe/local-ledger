# Changelog

Notable changes are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases use
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned

- Broader sanitized SMS-template coverage and reconciliation tools.
- Encrypted local backup and multi-account-per-bank support.

## [0.2.0] - 2026-09-06

### Fixed

- Recognize the observed transactional ICICI sender header while preserving its
  field-observed provenance separately from the frozen official registry.
- Parse additional ICICI, DCB Bank, and SBI debit/credit message families,
  including compact dates and direction-adjacent amounts.
- Keep content clear of status, navigation, and display-cutout system insets.

### Added

- Privacy-safe, seven-day/120-event rotating SMS pipeline diagnostics that can be
  copied from Settings without revealing message or financial content.
- Layered bank profiles plus a conservative generic parser fallback, parser IDs,
  confidence metadata, balance-clause masking, future-debit rejection, numeric or
  alphanumeric reference deduplication, and sanitized/adversarial regression tests.
- Modular, reorderable dashboard with global filters, bank-balance drilldown,
  trend/category/Sankey views, budget watch, insights, and recent activity.
- Hidden and deterministic demo display modes for sharing the UI safely.
- Transaction, merchant, and category tags with inherited effective tags.
- Manual transactions, merchants, categories, and tags.
- Category, merchant, or tag budgets across day/week/month/year periods, with
  pace projections and one notification per budget period.
- User-directed local CSV and PDF report exports.
- Build-time and release-APK checks that reject network permissions.

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

[Unreleased]: https://github.com/ashr-exe/local-ledger/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/ashr-exe/local-ledger/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/ashr-exe/local-ledger/releases/tag/v0.1.0
