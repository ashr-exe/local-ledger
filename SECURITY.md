# Security policy

## Supported versions

Local Ledger is currently alpha software. Security fixes are applied only to the
latest release line.

| Version | Supported |
|---|---|
| 0.1.x | Yes |
| Earlier snapshots | No |

## Reporting a vulnerability

Please use GitHub's **Report a vulnerability** form under the repository's
Security tab. Do not open a public issue for a vulnerability and do not include
real SMS messages, balances, account identifiers, signing material, or personal
data in a report.

Include the affected version, Android version, reproducible steps using synthetic
data, impact, and any suggested mitigation. You should receive an acknowledgement
within seven days. Publication timelines will be coordinated after a fix is
available.

## Security boundary

The app intentionally has no internet permission and does not retain full SMS
bodies. This reduces exposure but does not make the device, APK, or local database
invulnerable. A compromised or unlocked phone, malicious accessibility service,
modified APK, or rooted environment can defeat application-level controls.
