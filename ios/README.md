# GlucoTrail for iPhone — sensor reception prototype

This is an initial SwiftUI/Core Bluetooth app, not feature parity with Android.
It discovers devices advertising service `181F`, lets the tester explicitly select
a device, and decodes company `0059` glucose advertisements using the Android
protocol's CRC32 and packed-field layout. It never connects, activates a sensor,
writes a clock, or sends a command. Use an already activated sensor.

The screen includes Bluetooth permission/power status, signal strength, packet
validation status, reading age, sensor minute, quality, and mmol/L or mg/dL.
Invalid packets cannot become current readings. Repeated sensor minutes do not
refresh the reading's age. Old readings are explicitly historical after five
minutes, after invalid packets, or while scanning is stopped. Selection is by the
Core Bluetooth peripheral UUID; serial names may arrive in a later callback and
are never guessed from a manufacturer ID. Discovery state is bounded to 50 devices.

Scanning pauses when the app is inactive and resumes on return if the tester had
started it. Background Bluetooth, encrypted GATT, sensor activation, history
backfill, persistence/import, notes, insulin, fitness and alarms are not implemented.
The demo is prominently labeled and stops Bluetooth scanning. Real readings are
in memory only; no records are uploaded. No monitoring or treatment decisions
should rely on this prototype. No Apple distribution credentials are included.

## Build on a Mac

Requirements: Xcode with an iOS 16+ SDK, Swift 5.9+, and XcodeGen (`brew install xcodegen`).

```sh
cd ios
swift test
xcodegen generate
open GlucoTrail.xcodeproj
```

Choose the GlucoTrail scheme and an iPhone simulator. Simulator supports the UI/demo,
not actual sensor reception. GitHub's **iOS prototype validation** workflow runs
the Swift tests and builds the simulator app with signing disabled. Its ZIP artifact
is for a Mac's Simulator, **not an installable iPhone IPA**.

For your physical iPhone, choose your signing team in Xcode's Signing & Capabilities,
use a unique bundle ID if needed (the placeholder is `com.glucotrail.ios`), and run
on the connected device. Xcode may ask you to enable Developer Mode on the phone.
A personal development signing setup is for local testing; it does not provide
TestFlight distribution. Signing changes are local in the generated project.

## First device test

1. Keep the established monitoring app available. Open GlucoTrail on the iPhone,
   tap Start scan, and allow Bluetooth access. Use an already activated sensor.
2. Confirm the displayed sensor name/serial against your sensor, then select it.
3. Compare several new sensor minutes with the Android app: glucose, unit conversion,
   receipt time and signal. Check whether iOS delivers full 22-byte manufacturer data
   (2-byte company ID plus 20-byte payload) or only short scan-response data.
4. Stop scanning or move out of range. Verify historical/stale labeling and that
   repeated packets do not keep an old reading fresh. Try denying Bluetooth and
   turning Bluetooth off/on. Check text at larger accessibility font sizes.
5. Lock the phone, then return. Expect scanning to pause; this prototype does not
   promise readings or alarms while locked. Report packet status and reception gaps.

Android can inspect raw duplicate manufacturer records; Core Bluetooth exposes an
advertisement dictionary and may not deliver the same data. A passing parser test
does not establish that this sensor works on iOS. If the full broadcast is absent,
the next investigation is the authenticated GATT path, not inventing readings.

## Path to TestFlight and App Store

After real-device reception is proven, add authenticated GATT/background behavior,
storage/migration, and tested alerts before expanding the Android feature set.
For multiple users, enroll in the Apple Developer Program, register the final app
identifier and App Store Connect app, and configure signing plus App Store Connect
upload credentials as GitHub secrets. Then add a signed archive/upload workflow
for TestFlight and later App Store distribution. No signing/upload workflow is
enabled yet, and GitHub releases cannot replace Apple's installation mechanism.

References: [Apple Core Bluetooth background behavior](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html),
[TestFlight](https://developer.apple.com/testflight/),
[XcodeGen](https://github.com/yonaskolb/XcodeGen).
