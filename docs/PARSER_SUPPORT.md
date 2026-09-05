# SMS parser support

Bank alerts are not a stable protocol. Wording can vary by bank, payment rail,
transaction direction, account product, language, and template revision. Local
Ledger therefore does not claim that one sample per bank is exhaustive.

## Parsing strategy

The ingestion pipeline has independent layers:

1. Normalize the telecom routing prefix and message-category suffix.
2. Require a reviewed sender-to-bank match and a configured account.
3. Reject obvious OTP, marketing, failure, and unsupported credit-card messages.
4. Try narrow bank-specific profiles for known high-confidence families.
5. Fall back to conservative bank-agnostic extraction of direction, amount,
   date/time, reference, and counterparty.
6. Reject anything without enough evidence instead of inventing a transaction.
7. Record a privacy-safe outcome, parser ID, and confidence for troubleshooting.

This separation matters: a new message template normally requires a small parser
profile or a generic-parser improvement, not a new app architecture or sender
permission.

## Current high-confidence profiles

| Bank | Profile families |
| --- | --- |
| ICICI Bank | account debit; salary credit |
| DCB Bank | UPI debit; UPI credit |
| State Bank of India | UPI debit; UPI/account credit |

Other recognized banks use the generic parser until sanitized fixtures justify
a narrower profile. Generic parsing is intentionally conservative and may miss
unfamiliar alerts. A parsed SMS ledger is not statement reconciliation.

## Adding or updating support

Open an issue or pull request with:

- bank and normalized sender header;
- debit/credit and payment rail;
- the expected extracted fields;
- a fully invented or aggressively sanitized message retaining only its grammar;
- whether the app reported unknown sender, not parsed, or a wrong parsed field.

Never publish a real account fragment, reference number, balance, amount, name,
UPI ID, phone number, timestamp, or complete message. Prefer invented fixtures
such as `XX1234`, `SAMPLE STORE`, and clearly fake references. Tests must cover
both the new family and nearby non-transaction messages to prevent false imports.

## Why there is no bundled NLP model

A model would add APK size, CPU and memory cost, make failures less explainable,
and still would not guarantee correctness on unseen templates. Small reviewed
rules plus sanitized regression tests fit the offline/minimal product contract.
The architecture can add an optional on-device classifier later only if measured
false-positive/false-negative data shows that it materially outperforms the
deterministic pipeline within a strict resource budget.
