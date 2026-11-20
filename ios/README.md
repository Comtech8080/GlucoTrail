# GlucoTrail iOS — Phase 6 beta distribution

Native SwiftUI iPhone app (iOS 16+) with a Swift package using Foundation and system SQLite for protocol,
identity persistence, SQLite glucose history and deterministic connection policy. Android is unchanged.
The XcodeGen specification `project.yml` generates `GlucoTrail.xcodeproj`; generated
projects are intentionally untracked. Both build services run the same build script.

## Implemented

- Installed version/build, development release notes and previewable feedback sharing.
- Automatic unsigned Codemagic validation and a manually controlled signed App Store
  Connect upload workflow. See [Phase 6 setup and promotion](PHASE6.md). Account setup,
  signed device acceptance and actual TestFlight distribution remain owner steps.

- Health journal from History or Settings: timestamped notes with optimized photos,
  blood pressure, weight, administered-insulin records with chosen-duration countdowns,
  equipment catalogue and manual workout logging. See [Phase 5 details](PHASE5.md).
- User-initiated JSON export with optional glucose readings and photos. No import or
  automatic/cloud backup. Demo journal entries stay in memory and export as demo data.

- Configurable, opt-in local notifications and protected alert history under Settings →
  Alerts & safety. Read [Phase 4 behavior and physical validation](PHASE4.md) before use.
- Optional experimental background connection restoration/recovery. Glucose reception
  remains foreground-only; no reliable continuous background alarm claim is made.

- Foreground CoreBluetooth discovery of service 181F and strict AiDEX X serial selection.
- Explicit selection persists primary/replacement UUIDs and serials in an atomic local file.
  The former UserDefaults identity migrates to primary. Start scan restores saved UUIDs
  using CoreBluetooth retrieval, then scan fallback. A different UUID requires explicit
  selection even when its name matches. Forget clears both roles, preserving history.
- Connection, service/characteristic discovery, timeouts (20s connection, 15s discovery),
  five retries with 2/4/8/16/32-second backoff, and cancellation on Stop, sensor change,
  Bluetooth loss or inactive app. Cancellation callbacks drain before another attempt.
- Android-derived advertisement parser, checksum rejection, signed trends, bounded values,
  separate sensor timelines and stale labels. Repeated/older minutes do not refresh age.
- Decrypted F003 parser and an explicit wire-decoder gate that rejects encrypted input.
- Simulated connection-policy, identity relaunch/corruption, parser and freshness tests.
- Manual Codemagic unsigned workflow and GitHub PR validation: Swift tests, native Simulator
  build and native iPhone SDK build, with ZIP artifacts and Xcode result bundles.

## Deliberate protocol boundary

A connection is **not authenticated glucose streaming**. Phase 1 only discovers F001,
F002 and F003. It does not subscribe, read or write characteristics. Android's encrypted
handshake includes serial-derived IV/key requests, KEY0/KEY1 and setup commands; those
commands have not been validated on iOS. No activation, time-setting, calibration or
history commands are sent. `CurrentPacket.decodeWire` always rejects input. The separate
`decodeDecrypted` entry point is for fixtures and a future validated decryptor only.
See [PROTOCOL.md](PROTOCOL.md) for exact evidence and remaining validation work.

Live glucose is available only if iOS delivers the full supported advertisement. Some
AiDEX scan responses may expose only a short manufacturer record. Such records are
rejected; the app will not synthesize values or treat encrypted data as cleartext.
CRC integrity does not establish authenticity or clinical accuracy. This is an engineering prototype. Opt-in alerts, guarded dual-sensor handover and
experimental connection recovery are implemented, but continuous background glucose
and reliable background alarms are not validated. Continue established monitoring for care.

## Build and test

On a Mac with Xcode (Swift 5.9+) and XcodeGen:

```sh
brew install xcodegen
cd ios
swift test
xcodegen generate
open GlucoTrail.xcodeproj
# From repository root, on a fresh build directory:
bash ios/scripts/build-unsigned.sh
```

Select the GlucoTrail scheme. Simulator supports the screen/demo, not BLE. For local
physical-device testing, configure your signing team in the generated Xcode project.
The bundle identifier is `com.glucotrail.ios`; change it if your signing setup requires it.
Unsigned iPhone ZIPs prove compilation only: they are not installable IPAs. TestFlight
requires the separate signed workflow and account setup described in [Phase 6](PHASE6.md).

## Codemagic

Connect Comtech8080/GlucoTrail in Codemagic, select the branch containing this change,
choose **GlucoTrail iOS unsigned validation**, and start a build. No secrets,
Apple account or signing profile are needed for these unsigned builds. The workflow is
automatic on iOS changes pushed to main or ios-develop after webhook setup, and can
also be started manually. Account connection and actual Codemagic execution must be
done in the user's account; committing YAML does not activate them.
Check your account's included build allowance before running builds.

[Codemagic unsigned native builds](https://docs.codemagic.io/yaml-quick-start/first-signed-build/)
[Apple reconnect guidance](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/BestPracticesForInteractingWithARemotePeripheralDevice/BestPracticesForInteractingWithARemotePeripheralDevice.html)

## Physical-device acceptance checklist (not yet performed)

1. Use an already activated AiDEX X; confirm its serial before selecting it. Observe
   connecting, service discovery, and the explicit unauthenticated status.
2. Confirm full advertisements on actual iPhone/iOS/sensor firmware. Compare raw values
   and new-minute timing against Android; do not infer accuracy from simulation tests.
3. Move out of range, verify timeout/retry limits; return and Stop/Start after exhaustion.
4. Toggle Bluetooth, deny permission, lock/unlock, switch apps, force-quit and relaunch.
   Expect foreground-only behavior. Saved identity and glucose history should remain; restored readings must be marked historical.
5. Switch sensors during connecting and during retry. No old callback may reconnect the
   old sensor. A same-name/new-UUID device must require explicit selection.
6. Check invalid/short/duplicate packets never make a stale reading fresh. Verify Forget
   clears identity, demo stops connections, and larger accessibility text remains usable.
7. Before adding encrypted GATT, capture authorized Android/iPhone handshake fixtures,
   validate crypto against known vectors and test every command on supported hardware.


## Phase 2 dashboard and local history

The dashboard shows the last selected-sensor reading, Android-matched trend arrow,
receipt timestamp and freshness. Charts offer **2, 4, 6, 12, 24, 48 and 72 hours**,
with mmol/L or mg/dL display. A History tab browses saved sensor identities by local
calendar day, including identities that have been forgotten for connection purposes.

Validated advertisements from the explicitly selected sensor are saved in SQLite
under Application Support/GlucoseHistory/readings.sqlite. Unselected nearby sensors
are not recorded. Composite UUID + serial + sensor minute identity prevents duplicate
values and receipt-time refreshes across relaunches. Lower sensor minutes are ignored;
no rollover, sensor restart or replacement is inferred. SQLite schema version 1 is
created transactionally; unknown versions and corrupt files fail without deleting data.
Write/read errors are shown in the app. A failed insert can be retried on a later packet.

- Only receipt timestamps are plotted; no sensor wall-clock timestamps or previous-value
  timestamps are invented. A new receipt is not proof of a recent sensor measurement.
- Lines break at missing minutes, >=5-minute receipt gaps, identity changes and clock
  discontinuities. Single readings remain visible. No smoothing or fabricated backfill.
- At five minutes without a new reading, the displayed value is stale. Stopped reception,
  rejected packets and restored history cannot become current because of a duplicate.
  Historical trend arrows are labelled as the trend at the last reading.
- Demo data is generated in memory with its own labelled identity. Enabling demo stops
  Bluetooth. It never writes to the glucose database; disabling it does not restart BLE.
- History is retained locally without automatic purging; the directory uses iOS complete
  file protection and is excluded from backups. Uninstalling the app removes this history.
  Forgetting the connection identity does not delete past readings.
- Bluetooth connection status is separate from advertisement reception and data age.
  Encrypted GATT remains disabled; this phase adds no sensor commands or background modes.

Run `bash ios/scripts/test-ui.sh` after project generation for the iPhone Simulator
smoke test. Both build services run it; its result bundle includes a dashboard screenshot.
Swift tests cover database reopen/deduplication, isolated identities, schema/corruption
handling, write-lock failure/retry, all chart windows, gaps, freshness and trend bands.

## Phase 3: replacement and overlap

Version 0.4.0 adds two independently reconnecting sensor roles. Adding a replacement
keeps the primary connection and records both validated advertisement streams in the
existing SQLite history. The Replacement tab provides primary/replacement/combined
graphs, raw differences and an optional, unsaved meter-reference comparison. No
calibration, glucose correction or sensor command is performed.

Manual handover requires three consecutive new sensor minutes after minute 60,
received no more than 90 seconds apart, with the latest less than 120 seconds old.
Readiness is rechecked at confirmation. Automatic handover is OFF by default and
must be explicitly enabled for the current foreground session. It additionally
requires a primary reading accepted in that uninterrupted session, followed by
15 minutes without a new primary reading. This is an application policy, not a
validated expiry detector or a clinically validated switching rule.

Stop, backgrounding, Bluetooth loss, relaunch, backward clock movement or a timer
gap over 90 seconds clears eligibility and automatic consent. Duplicate and older
minutes cannot restore readiness. A failed role save leaves roles unchanged and
disarms automatic handover. Each sensor has an independent bounded connection retry
state. Old callbacks cannot promote an unselected sensor.

Roles and the last 200 transitions are saved together by atomic replacement in
sensor-pair.json in the protected, backup-excluded history directory. Phase 1's
saved identity migrates to primary. Corrupt files are reported without erasure.
Switching or forgetting roles preserves both sensors' histories. After handover,
overlap graphs retain the previous and new primary streams.

Live comparison requires both post-warm-up readings to be less than five minutes
old and received within two minutes of one another. Meter references are neither
saved nor sent to the sensor. Demo handovers use memory-only identities and never
change real roles or history. Android code and the BLE protocol boundary are unchanged:
encrypted GATT and activation remain unavailable. Phase 4 adds opt-in alerts and
experimental connection recovery, without continuous background glucose guarantees.

Tests cover readiness, stale/replayed packets, interrupted sessions, auto-handover
timing, independent reconnect state, atomic persistence and raw comparisons. UI tests
exercise demo overlap modes, handover and isolation from real roles. Existing GitHub
Actions and Codemagic workflows run the expanded tests and unsigned builds. Unsigned
artifacts cannot be installed directly on an iPhone; signing is still required.

Before physical use, test with two already activated sensors: verify both serials,
compare raw advertisements with Android, interrupt each connection independently,
exercise Stop/Start, Bluetooth off/on and background/foreground, verify no automatic
handover across these interruptions, then test manual and explicitly enabled automatic
handover while observing both devices. Confirm histories survive relaunch. Simulator
tests do not validate real radio reception, sensor accuracy or clinical suitability.

Physical acceptance still required: verify saved Android-comparable readings across app
relaunch, sensor selection, Bluetooth interruptions, midnight/daylight-saving changes,
unit changes and large accessibility text. Simulator tests do not prove sensor reception.
