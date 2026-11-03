# Publishing GlucoTrail updates

## Signing continuity is required

Use the **same signing key as the GlucoTrail build already installed on the phone**.
Do not generate a replacement key or uninstall the app to bypass a signing error.
The application ID remains `com.aidexx.reader`. A normal Android update with this
ID and signing key retains databases, photos, preferences, and other app data.
This change adds no data migration or data deletion.

The previous PowerShell builds may have used the developer machine's debug key.
Determine the actual installed certificate before choosing a release key. For a
locally built APK, run `apksigner verify --print-certs path/to/existing.apk`.
Compare with the intended signed release APK. Keep the existing key backed up
securely. If the original key is unavailable, an in-place update cannot be promised.
Keys, passwords, APKs, health records, local SDK paths, and build caches are excluded
from source control. Do not paste keys or passwords into issues or commits.

## GitHub Actions setup

Add these repository Actions secrets yourself using the existing keystore:

| Secret | Value |
| --- | --- |
| `GLUCOTRAIL_KEYSTORE_BASE64` | Base64 of the existing signing keystore |
| `GLUCOTRAIL_STORE_PASSWORD` | Keystore password |
| `GLUCOTRAIL_KEY_ALIAS` | Existing key alias |
| `GLUCOTRAIL_KEY_PASSWORD` | Key password |

No secrets are supplied or created by this PR. Pull request validation and local
debug builds run without them. The release workflow fails with a setup message
if any are missing; it never substitutes a generated key. `GITHUB_TOKEN` is
provided automatically by Actions and is granted contents-write only in the
release job. Local `assembleRelease` without signing environment variables produces
an unsigned APK for validation, which the packaging tool refuses to publish.

1. Increment `versionCode` for **every** release, including betas. Set `versionName`
   in `app/build.gradle.kts` (for example `0.1.60.20` or `0.1.60.21-beta.1`).
2. Merge reviewed code, then push the matching tag, such as `v0.1.60.20`.
   Alternatively run the workflow manually with an existing matching tag.
3. Actions tests, lints, builds, verifies the signature, and creates a **draft**
   GitHub Release containing `GlucoTrail-vVERSION.apk`, its `.apk.sha256`, and
   `update.json` generated from Gradle's actual APK metadata. Existing releases
   are not overwritten. Hyphenated version tags are marked prereleases.
4. Review/edit release notes and test an in-place update on a phone with the
   previous version and matching signing key. Publish the complete draft when ready.
   Drafts cannot appear in the app; prereleases appear only when beta is enabled.

For a manual signed build, set `GLUCOTRAIL_KEYSTORE` to a local keystore path plus
the three password/alias environment variables above. Run `assembleRelease`,
verify using `apksigner`, then `python tools/package_release.py vVERSION`.
Upload all three generated assets together to a draft release before publishing.

`update.json` declares `applicationId`, numeric `versionCode`, `versionName`, and
the exact APK filename. The checksum uses standard `sha256sum` format. The app
scans the latest 100 releases and selects the highest Android versionCode in the
chosen channel; legacy releases without update.json are skipped. Always keep the
current supported release within that window. Turning beta off never downgrades
an installed beta; a newer stable versionCode is required.

The first updater-enabled build must be installed manually over the current app
with the matching signing key. Thereafter Settings → App Updates can install
published releases. Android requires permission to install from GlucoTrail and
asks for confirmation on every update. Silent installation is not implemented.
