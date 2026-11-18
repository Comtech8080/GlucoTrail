# GlucoTrail iOS — Phase 4 notifications and safety validation

Native SwiftUI iPhone app (iOS 16+) with a Swift package using Foundation and system SQLite for protocol,
identity persistence, SQLite glucose history and deterministic connection policy. Android is unchanged.
The XcodeGen specification `project.yml` generates `GlucoTrail.xcodeproj`; generated
projects are intentionally untracked. Both build services run the same build script.

## Implemented

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
CRC integrity does not establish authenticity or clinical accuracy. This is a foreground
engineering prototype with no glucose alarms, background monitoring, dual-sensor handover,
or TestFlight distribution. Continue established monitoring for care.

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
  rejected packets and re…1201 tokens truncated…        app.launch()
        app.tabBars.buttons["Settings"].tap()
        let link = app.buttons["alertsSettings"]
        for _ in 0..<4 where !link.isHittable { app.swipeUp() }
        link.tap()
        XCTAssertTrue(app.staticTexts["notificationPermission"].waitForExistence(timeout: 5))
        app.buttons["alertPreviewLink"].tap()
        for (button, expected) in [("previewLow", "low"), ("previewHigh", "high"), ("previewStale", "stale"), ("previewDisconnected", "disconnected")] {
            app.buttons[button].tap()
            XCTAssertEqual(app.staticTexts["alertPreviewResult"].label, "DEMO · \(expected) request eligible")
        }
        for button in ["previewDelayed", "previewStopped"] {
            app.buttons[button].tap()
            XCTAssertEqual(app.staticTexts["alertPreviewResult"].label, "DEMO · No alert eligible")
        }
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Phase 4 isolated alert preview"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let state = app.staticTexts["alertsEnabledState"]
        for _ in 0..<4 where !state.isHittable { app.swipeUp() }
        XCTAssertEqual(state.label, "Alerts: OFF")
        XCTAssertFalse(app.buttons["alertEnableButton"].isEnabled)
        let empty = app.staticTexts["emptyAlertHistory"]
        for _ in 0..<8 where !empty.isHittable { app.swipeUp() }
        XCTAssertTrue(empty.exists)
    }

    func testSimulatedReplacementHandoverIsIsolated() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        app.tabBars.buttons["Replacement"].tap()
        XCTAssertTrue(app.staticTexts["pairPrimary"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["pairPrimary"].label.contains("DEMO000001"))
        XCTAssertTrue(app.staticTexts["pairReplacement"].label.contains("DEMO000002"))
        XCTAssertEqual(app.staticTexts["replacementReadiness"].label, "Ready for handover")
        let modes = app.segmentedControls["overlapMode"]
        for mode in ["Primary", "Replacement", "Compare"] {
            for _ in 0..<4 where !modes.isHittable { app.swipeUp() }
            modes.buttons[mode].tap()
            XCTAssertTrue(modes.buttons[mode].isSelected)
        }
        XCTAssertTrue(app.otherElements["overlapChart"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Phase 3 sensor overlap"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        let handover = app.buttons["handoverButton"]
        for _ in 0..<4 where !handover.isHittable { app.swipeDown() }
        handover.tap()
        app.buttons["Confirm handover"].tap()
        let switched = NSPredicate(format: "label CONTAINS %@", "DEMO000002")
        expectation(for: switched, evaluatedWith: app.staticTexts["pairPrimary"])
        waitForExpectations(timeout: 5)
        XCTAssertEqual(app.staticTexts["pairReplacement"].label, "Replacement: None")
        app.tabBars.buttons["Dashboard"].tap()
        app.tabBars.buttons["Replacement"].tap()
        XCTAssertTrue(app.staticTexts["pairPrimary"].label.contains("DEMO000002"))
        app.tabBars.buttons["Settings"].tap()
        app.buttons["demoButton"].tap()
        app.tabBars.buttons["Replacement"].tap()
        XCTAssertEqual(app.staticTexts["pairPrimary"].label, "Primary: None")
        XCTAssertEqual(app.staticTexts["pairReplacement"].label, "Replacement: None")
    }

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    func testDemoDashboardRangesHistoryAndUnits() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        XCTAssertTrue(app.staticTexts["demoBanner"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["latestGlucose"].exists)
        let initialValue = app.staticTexts["latestGlucose"].label
        for hours in [2, 4, 6, 12, 24, 48, 72] {
            app.buttons["graphRange"].tap()
            app.buttons["\(hours) hours"].tap()
            XCTAssertTrue(app.staticTexts["graphWindowLabel"].label.contains("\(hours)-hour"))
        }
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Phase 2 dashboard — 72 hours"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.staticTexts["DEMO · sample history only"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.datePickers.firstMatch.exists)
        app.tabBars.buttons["Settings"].tap()
        let units = app.segmentedControls["unitPicker"]
        XCTAssertTrue(units.waitForExistence(timeout: 5))
        let alternateUnit = initialValue.contains(".") ? "mg/dL" : "mmol/L"
        units.buttons[alternateUnit].tap()
        XCTAssertTrue(units.buttons[alternateUnit].isSelected)
        app.tabBars.buttons["Dashboard"].tap()
        XCTAssertNotEqual(app.staticTexts["latestGlucose"].label, initialValue)
        app.tabBars.buttons["Settings"].tap()
        app.buttons["demoButton"].tap()
        XCTAssertTrue(app.buttons["Show demo data"].waitForExistence(timeout: 5))
        app.tabBars.buttons["Dashboard"].tap()
        XCTAssertTrue(app.staticTexts["No glucose reading yet"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["demoBanner"].exists)
        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.staticTexts["No readings for this sensor and date."].waitForExistence(timeout: 5))
    }
}
