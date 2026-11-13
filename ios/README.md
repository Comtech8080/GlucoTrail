# GlucoTrail iOS — Phase 2 dashboard and history

Native SwiftUI iPhone app (iOS 16+) with a Foundation-only Swift package for protocol,
identity persistence, SQLite glucose history and deterministic connection policy. Android is unchanged.
The XcodeGen specification `project.yml` generates `GlucoTrail.xcodeproj`; generated
projects are intentionally untracked. Both build services run the same build script.

## Implemented

- Foreground CoreBluetooth discovery of service 181F and strict AiDEX X serial selection.
- Explicit selection persists the peripheral UUID and advertised serial in local UserDefaults.
  Start scan restores that UUID using CoreBluetooth retrieval, then scan fallback. A different
  UUID requires explicit selection even when its name matches. Forget clears the saved identity.
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
CRC integrity does not establish authenticity or clinical accuracy. This is a foreground
engineering prototype with no glucose alarms, background monitoring, dual-sensor handover,
TestFlight distribution. Continue established monitoring for care.

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
requires a separate signed distribution setup, which this workflow does not configure.

## Codemagic

Connect Comtech8080/GlucoTrail in Codemagic, select the branch containing this change,
choose **GlucoTrail iOS unsigned validation**, and start a build. No secrets,
Apple account or signing profile are needed for these unsigned builds. The workflow is
manual to avoid unsolicited recurring builds. Account connection and actual Codemagic
execution must be done in the user's account; committing YAML does not activate them.
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

Physical acceptance still required: verify saved Android-comparable readings across app
relaunch, sensor selection, Bluetooth interruptions, midnight/daylight-saving changes,
unit changes and large accessibility text. Simulator tests do not prove sensor reception.
