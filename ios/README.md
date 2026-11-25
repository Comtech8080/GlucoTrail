# GlucoTrail iOS — Phase 1 sensor foundation

Native SwiftUI iPhone app (iOS 16+) with a Foundation-only Swift package for protocol,
identity persistence and deterministic connection policy. Android is unchanged.
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
history database or TestFlight distribution. Continue established monitoring for care.

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
choose **GlucoTrail iOS Phase 1 unsigned validation**, and start a build. No secrets,
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
   Expect foreground-only behavior. Saved identity should remain; glucose is not persisted.
5. Switch sensors during connecting and during retry. No old callback may reconnect the
   old sensor. A same-name/new-UUID device must require explicit selection.
6. Check invalid/short/duplicate packets never make a stale reading fresh. Verify Forget
   clears identity, demo stops connections, and larger accessibility text remains usable.
7. Before adding encrypted GATT, capture authorized Android/iPhone handshake fixtures,
   validate crypto against known vectors and test every command on supported hardware.
