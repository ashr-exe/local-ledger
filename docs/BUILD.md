# Build and install

## Requirements

- Android Studio with Android SDK 36 and JDK 17, or equivalent command-line tools
- Android 8.0 (API 26) or newer phone with SMS capability

## Build

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug verifyOfflineContract
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The Gradle wrapper is committed, so a separate Gradle installation is not
required. CI runs the same unit-test, lint, and debug-build tasks on every pull
request and push to `main`.

## Install

Enable installation from your chosen file manager, copy the APK to the phone, and open it. Alternatively, with USB debugging enabled:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first run:

1. Select each supported bank and enter the account's current balance.
2. Tap **Start tracking**.
3. Grant SMS receive/read access. Receive access captures new messages; read access lets the app recover messages received after tracking started while it was stopped.

Only one account per bank is supported in this version. Explicit credit-card transaction alerts are ignored because cards are not part of the account setup yet.

### If SMS permission is blocked

Sensitive permissions may be restricted for sideloaded apps:

1. Open **Settings → Apps → Local Ledger**.
2. Open the three-dot menu and choose **Allow restricted settings**.
3. Return to **Permissions → SMS** and allow access.
4. Open Local Ledger → **Settings → Scan for missed messages**.
5. If nothing imports, use **Copy privacy-safe diagnostics** and attach the
   result to a bug report. It contains no SMS body or financial values.

Wording varies by Android version and OEM. A force-stopped app cannot receive
SMS broadcasts until it is opened again.

### Play Protect

Google Play Protect may warn about or block an unverified sideloaded APK with
sensitive access. Install only the signed GitHub release after verifying
`SHA256SUMS`. Prefer **Install anyway** when offered. If your device provides no
alternative and you deliberately pause **Play Store → Play Protect → Settings →
Scan apps with Play Protect**, re-enable it immediately after installation.
Google recommends leaving Play Protect enabled.

## Release builds

An unsigned, optimized build can be produced locally with:

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Never distribute that unsigned output or a debug APK as a production release.
Tagged GitHub builds use the protected secrets and signing procedure documented
in [RELEASING.md](RELEASING.md).

## Sender registry

The official `headers` arrays in
`app/src/main/assets/bank_sender_registry.json` are generated from TRAI's
compiled workbook published on 16 June 2020. Separately reviewed corrections use
`observedHeaders` so provenance is not blurred. Rebuild the official snapshot with:

```sh
python tools/extract_trai_registry.py path/to/List_SMS_Headers_16062020.xlsx \
  app/src/main/assets/bank_sender_registry.json
```

TRAI source: https://www.trai.gov.in/node/7411

The first two routing characters and the message-category suffix are stripped before lookup. For example, `VD-KOTAKB-T` is matched as `KOTAKB`.

Never add a private message verbatim. Add a fully invented fixture that preserves
only the template grammar, and document any observed header without names,
account fragments, amounts, phone numbers, references, or timestamps.
