# Phase 6: beta distribution

Version 0.7.0 adds automatic unsigned Codemagic validation, a manually selected signed
upload workflow, installed-build information, bundled development release history,
and a feedback template that the tester can inspect before sharing. Android and the
sensor/alert implementations are unchanged. This is distribution infrastructure,
not evidence that the sensor integration is medically validated.

## What works without an Apple account

GitHub iOS validation runs release-tool tests, Swift package tests, unsigned Simulator
and iPhone builds, native photo/metadata tests, and Simulator UI tests. Codemagic's
`ios-unsigned` performs the same checks. It now watches pushes to `main` and
`ios-develop`, restricted to iOS/configuration changes, and cancels superseded builds.
Feature branches can still be built manually. Connect the repository and configure
its Codemagic webhook to activate this. YAML alone does not connect accounts or
enable paid usage; check your account allowance and billing settings.

Unsigned ZIP artifacts cannot be installed on a physical iPhone.

## One-time Apple and Codemagic setup

1. Enroll in the Apple Developer Program if not already enrolled. This is an account
   purchase made by the owner, not by the repository workflow.
2. Register the explicit identifier `com.glucotrail.ios` in your Apple team and create
   the matching iOS app record in App Store Connect. If that identifier is unavailable,
   update it consistently in `project.yml`, `codemagic.yaml`, and `scripts/release.py`
   before building. Changing an installed app's identifier creates a different app
   with separate local data.
3. Create an App Store Connect API key with App Manager access. In Codemagic's Apple
   integration, save it under the exact name **GlucoTrail App Store Connect**. Store
   credentials there, never in GitHub files, chat, or build logs.
4. In Codemagic code-signing settings, provide an Apple Distribution certificate with
   its private key and an App Store provisioning profile for the same team and bundle
   identifier. The `ios_signing` configuration selects these uploaded files; it does
   not create them. Refresh expired/revoked profiles before starting a release.
5. Create the Codemagic variable group `glucotrail_beta_release`. Restrict access to
   release maintainers and do not expose it to pull-request workflows. Set the three
   variables below separately for each release. Only run trusted, reviewed source.
6. Complete Apple's app/beta information, privacy policy, contact details, export
   compliance questions and review information. The code does not assert an encryption
   exemption or invent a privacy-policy URL. Review the actual app and disclosures.

## Build and upload one reviewed candidate

The signed `ios-beta` workflow has **no automatic trigger**. Automatic validation is
separate from publication. It uploads a candidate to App Store Connect for TestFlight
processing; it does not request beta review, invite testers, or release to the App Store.

1. Select a commit whose GitHub iOS and Android checks have passed. Set:

   | Variable | Required value |
   | --- | --- |
   | `IOS_RELEASE_SHA` | Full 40-character lowercase Git commit SHA |
   | `IOS_BUILD_NUMBER` | Unused integer, at least 7 and at most 9999; greater than all prior uploaded builds |
   | `IOS_RELEASE_ACTION` | `upload-only` |

   Build numbers are explicitly reserved by the maintainer, not inferred from a CI
   run counter. Check App Store Connect before choosing one. Run only one signed
   release at a time; retries after an accepted upload require a new build number.
   This workflow does not query Apple to reserve or check number uniqueness.
2. Start **GlucoTrail iOS controlled beta upload** manually at that commit/branch.
   Preflight rejects a moved branch, missing approval values, invalid build numbers,
   mismatched notes and dirty source. The expected SHA is a selection guard, not an
   independent authorization system; maintainers control the workflow and variables.
3. The workflow runs release-tool tests, all Swift tests and all native/UI tests before
   applying signing profiles and archiving. It overrides the archive build number but
   keeps the reviewed marketing version. It checks the resulting IPA identity, version,
   build and presence of signing/privacy files before publishing. Apple's upload service
   performs cryptographic signature and provisioning validation.
4. Retain the IPA, dSYMs, result bundle, `release-record.json` (commit, version, build,
   IPA SHA-256) and `what-to-test.txt`. Copy the latter into TestFlight's What to Test.
   Release notes are not automatically sent to testers.
5. Confirm successful Apple processing in App Store Connect, including export compliance.
   A green compilation job or an uploaded artifact alone is not proof of availability.
   Clear `IOS_RELEASE_ACTION` after the run to prevent an accidental repeat upload.

## Controlled promotion and installation

Keep automatic distribution **off in App Store Connect groups**, including internal
groups, otherwise Apple's existing settings can distribute newly uploaded builds even
though this workflow does not select groups.

1. **Internal alpha:** manually add the exact processed version/build to a small internal
   TestFlight group. Internal testers require appropriate App Store Connect access;
   do not grant account access merely to recruit ordinary testers. On the iPhone,
   install Apple's TestFlight app, accept the invitation, then tap Install.
2. **Invite-only external beta:** after internal acceptance, complete beta review details,
   submit the same candidate for Apple's required review, then invite a small external
   group by email. Record the approved version/build and acceptance evidence.
3. **Public beta:** only after physical acceptance, privacy/claims review and explicit
   owner approval, enable a public link for a separately controlled external group.
   No public link, invitation or automatic public rollout is created by this code.

Prefer promoting the same tested build. If code changes, create a new candidate and
repeat validation. For a faulty beta, stop invitations/public links and expire the
affected build in App Store Connect. Use a still-valid previously tested build only
after checking data compatibility, or ship a corrected build with a higher number.
Do not uninstall as a troubleshooting step without exporting needed data: app removal
deletes local history and the JSON export currently has no restore function.

## Version notes, feedback and privacy

Settings → Beta information & feedback reads the **installed bundle's** version and
build, including the signed workflow's override. ReleaseHistory.json describes
development milestones and does not claim those versions were publicly distributed.

Prepare feedback shows a template containing app version/build, iOS version and demo
or real-history mode. It does not access glucose, sensor IDs, device name, journal,
photos, logs or account identity. Share opens the system sheet only when tapped; the
tester chooses a recipient and completes sending. No issue, email, analytics upload
or crash service is created automatically. TestFlight's own feedback/crash facilities
are available for TestFlight installations. Review screenshots and added text first.

The app privacy manifest declares no app-managed tracking or remote data collection,
and the app's own preferences/file-metadata API use. User-selected Files providers,
share destinations and Apple's TestFlight service have their own data handling.
App Store privacy answers and a published privacy policy still require owner review.

## Acceptance still required before wider distribution

- Signed archive, real Codemagic execution, Apple processing, TestFlight install/update,
  and dSYM availability must be demonstrated in the owner's accounts.
- Verify actual installed version/build, feedback preview/share/cancel, release notes,
  icon, large text, Photos selection and Files export on an iPhone.
- Verify an update preserves sensor roles, glucose history, alert settings and journal.
  Exercise Bluetooth permission denial, foreground/background/lock/force-quit recovery
  and two-sensor behavior using the checklists in README/PHASE4/PHASE5 documentation.
- Keep using established monitoring. Simulation, distribution and signing do not
  validate glucose accuracy, encrypted GATT or reliable background alarms.

## Sources for account setup

- [Codemagic native iOS signing/build configuration](https://docs.codemagic.io/yaml-quick-start/building-a-native-ios-app/)
- [Codemagic App Store Connect upload and review controls](https://docs.codemagic.io/yaml-publishing/app-store-connect/)
- [Codemagic automatic build triggers and webhooks](https://docs.codemagic.io/yaml-running-builds/starting-builds-automatically/)
- [Apple TestFlight overview](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/)
- [Apple external testers and public links](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers/)
