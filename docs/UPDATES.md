# Update behavior and validation

Updates are for the GitHub-distributed build. Checks contact public GitHub endpoints
without an embedded token or personal/health data. Automatic checks default on,
run approximately daily when Android allows network work, persist across reboot,
and can be disabled. The app never downloads or installs automatically. Notifications
use a separate channel, respect Android permission, and open App Updates directly.

The updater accepts only HTTPS assets in this repository. Redirects are limited to
GitHub's download hosts; downloads have timeouts and byte limits. SHA-256 must match
the accompanying checksum. Package ID, actual versionCode, versionName, and exact
current signer set must match the expected update. Certificate rotation is deliberately
not accepted; supporting a rotation lineage requires a separate tested migration.
Android performs final APK signature validation during installation. SHA-256 alone
is not proof of authorship; the installed signing certificate is the trust anchor.

The APK is staged in a private cache subdirectory. A non-exported FileProvider grants
temporary read access only under that directory. Partial or failed downloads are
removed. The APK is revalidated before every installation attempt. Rotation retains
screen state and download through a ViewModel; process death requires a fresh download.
Background downloads are not guaranteed to survive Android terminating the process.
No storage permission or public Downloads folder is used. Application data is untouched.

## Automated checks

`testDebugUnitTest` covers permitted asset origins, checksum format and filename,
package mismatches, downgrades, metadata mismatch, missing/wrong/multiple signers.
`python -m unittest discover -s tools -p 'test_*.py'` tests release asset generation.
CI runs Android unit tests, debug assembly, and lint without release secrets.

## Device checks before publishing

- Open Settings → App Updates; check installed/latest versions, notes and release link.
- With no release assets, show an empty state; offline/rate limited requests show a
  retryable error rather than offering installation.
- Publish a test stable/beta pair: toggle beta and verify the highest eligible
  versionCode. Disable beta from an installed newer beta and verify no downgrade.
- Download, cancel, retry, rotate, leave/reopen the screen, and interrupt the network.
  Verify progress and that no partial APK can be installed.
- Tamper with the APK/checksum, use a different signing key, different application ID,
  older version, or false metadata. Each must be refused before installation.
- Deny install permission, grant it, cancel the Android installer, then retry.
- Enable/deny notification permission; disable the channel and automatic checks.
  Verify scheduled notifications open App Updates and do not bypass preferences.
- Install a matching signed update over a seeded previous version. Confirm sensor
  settings, glucose history, notes/photos, insulin and fitness records remain.
- Reboot and check scheduling. Android may delay jobs for power/network restrictions.

These device cases require a phone/emulator and the existing signing key. A successful
compile alone does not establish on-device update behavior or preservation of records.
