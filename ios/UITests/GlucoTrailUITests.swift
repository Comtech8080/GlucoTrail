import XCTest

final class GlucoTrailUITests: XCTestCase {
    func testAlertPreviewsNeverEnableRealNotifications() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
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
