# Contributing

Local Ledger handles sensitive financial metadata and privileged SMS access.
Changes should preserve its offline boundary, small dependency surface, and
defensive parsing model.

## Before opening a change

1. Search existing issues and discussions.
2. For substantial product or schema changes, open a proposal issue first.
3. Never include real SMS bodies, account identifiers, phone numbers, signing
   files, tokens, or other personal data in an issue, fixture, commit, or log.

## Local workflow

1. Use JDK 17 and Android SDK 35.
2. Create a topic branch from `main`.
3. Make the smallest coherent change.
4. Add sanitized tests for parser or data-layer behavior.
5. Run:

   ```sh
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```

6. Open a pull request using the repository template.

## SMS fixtures

Parser tests must use invented examples. Replace account fragments, reference
numbers, merchant identifiers, phone numbers, and timestamps. A sender header may
be retained only when it is already present in the public TRAI-derived registry.

## Design expectations

- Do not add `INTERNET` permission, telemetry, ads, or remote configuration.
- Avoid dependencies when Android or Kotlin standard APIs are sufficient.
- Reject uncertain messages rather than silently creating confident-looking data.
- Keep schema migrations forward-only and covered by tests.
- Treat accessibility, battery usage, and low-end-device performance as release
  requirements.

## Commit and review quality

Use focused, imperative commit subjects. Pull requests should explain the user
impact, risk, validation performed, and any privacy or permission change. At least
one maintainer approval and a green required-check set are expected before merge.
