# Release process

Releases must be signed, reproducible from a tagged commit, and accompanied by a
checksum. Never commit a keystore or password.

## One-time repository setup

1. Create a dedicated Android release-signing keystore and back it up securely
   outside the repository. For APKs distributed directly through GitHub, this is
   the app-signing key and must remain available for the app's lifetime.
2. Add these GitHub Actions secrets:
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
3. Enable private vulnerability reporting, Dependabot alerts, secret scanning,
   push protection, and branch protection for `main`.
4. Require the Android CI and CodeQL checks plus one approving review.

Encode the keystore without line breaks:

```sh
base64 < release.jks | tr -d '\n'
```

Store the output only in the GitHub secret—not in shell history, an issue, a
commit, or project documentation.

## GitHub releases and Google Play

The workflow signs GitHub Release APKs directly, so
`ANDROID_KEYSTORE_BASE64` must contain the permanent key used for those APKs.
Google Play App Signing can instead keep the Play-distributed app-signing key and
let the developer use a separate, resettable upload key. Decide whether releases
across GitHub, Play, and other stores must share one signing identity before the
first public APK is installed; Android updates normally require the signing
certificate expected by the installed app.

## Cutting a release

1. Confirm the changelog date, version name, and version code.
2. Run `./gradlew clean testDebugUnitTest lintDebug assembleRelease` locally.
3. Merge through a reviewed pull request with all required checks green.
4. Create and push an annotated tag such as `v0.1.0`.
5. The release workflow builds and signs the APK, generates `SHA256SUMS`, and
   creates a GitHub Release with generated notes.
6. Download the published APK, verify its certificate and checksum, then perform a
   clean-device smoke test before marking a pre-release stable.

## Verification

```sh
sha256sum -c SHA256SUMS
apksigner verify --verbose --print-certs local-ledger-0.1.0.apk
```
