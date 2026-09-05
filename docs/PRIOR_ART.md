# Open-source parser research

Research reviewed on 6 September 2026. Claims below describe the linked projects
at that point in time; they are not endorsements or dependencies.

## Relevant projects

- [omoi-sms-parser](https://github.com/abhirajsinha/omoi-sms-parser) is a tiny,
  deterministic Indian bank/UPI parser. Its most useful ideas are a distinct DLT
  sender-authority gate, ordered message classification, balance-clause masking,
  integer money, and a permanent adversarial non-transaction corpus.
- [Salli](https://github.com/leadsgen-tech/salli) uses stateless per-bank Kotlin
  templates and a closed parse-result type. Recognized-but-unparsed bank messages
  enter a local triage queue; it also has explicit duplicate, fee-pair, and
  internal-transfer detectors.
- [Expense Tracker](https://github.com/allwin-antony/Expense_tracker) combines a
  multi-clause transaction scoper, regex extraction, and a very small quantized
  on-device classifier. The clause scoper is valuable independently of ML because
  it prevents an available-balance sentence from supplying the transaction amount.
- [MoneyPrism](https://github.com/qtw4c7phzx-alt/Moneyprism) separates country
  parsers behind a registry, ships a paste-and-test surface, and hashes raw SMS for
  deduplication.
- [Cashiro](https://github.com/ritesh-kanwar/Cashiro) demonstrates broader account,
  statement-import, subscription, and multi-currency directions, but its larger
  feature/runtime surface is beyond Local Ledger's present boundary.
- [UPI Expense Tracker](https://github.com/xxwarwolfxx/UPI-Expense-Tracker) also
  strips Android's internet permission and deduplicates with payment references.
  It supplements SMS using an Accessibility Service; Local Ledger will not adopt
  that permission because it materially expands access and background complexity.

## Adopted in Local Ledger 0.2

- Sender identity grants booking authority; message wording alone never does.
- Bank-specific profiles sit above a conservative generic grammar.
- Classification vetoes run before extraction: bound OTP, failed/declined,
  promotional, future debit, unsupported card, then completed transaction.
- Balance/limit clauses are removed from generic amount selection.
- Reference identifiers support numeric and common alphanumeric UTR forms and
  deduplicate repeated alerts within the same bank and direction.
- Money remains integer paise throughout.
- Invented regression examples cover successful formats; a grow-only adversarial
  suite covers messages that must create no transaction.
- Every failure stage is visible through a small, rotating, privacy-safe local
  diagnostic trail.

## Useful ideas deferred

- **Local unknown-message triage:** useful for discovering formats, but storing raw
  SMS conflicts with the current minimal-data promise. A future design should let
  the user inspect and explicitly sanitize/share an unknown message without adding
  it to ordinary logs or exports.
- **Reference-plus-signal reconciliation:** match SMS against imported bank
  statements to find missing alerts. This is the clearest path to higher confidence
  without pretending SMS alone is authoritative.
- **Internal-transfer pairing:** likely match opposite directions across the user's
  banks using reference, amount, account suffix, and time. Amount/time alone is too
  weak and could hide genuine income or expense.
- **On-device classifier:** consider only as a secondary classifier after a measured
  corpus proves deterministic rules insufficient. It must never invent amounts or
  bypass sender authority, and must justify its APK/RAM/CPU cost.
- **Paste-to-test developer tool:** valuable for contributors, but it should live in
  unit-test tooling or a debug-only build rather than the production APK.

## Resulting reliability position

Template breadth improves recall; ordered vetoes, sender authority, clause masking,
and adversarial tests protect precision. Neither approach makes an SMS-derived
ledger complete. Statement reconciliation remains necessary for authoritative
coverage because an app cannot parse an alert that was never generated or delivered.
