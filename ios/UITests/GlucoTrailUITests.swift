import XCTest

final class GlucoTrailUITests: XCTestCase {
    func testBetaReleaseNotesAndFeedbackPreview() {
        let app = XCUIApplication(); app.launchArguments = ["--demo"]; app.launch()
        app.tabBars.buttons["Settings"].tap()
        let beta = app.buttons["betaInformation"]
        for _ in 0..<6 where !beta.isHittable { app.swipeUp() }
        beta.tap()
        XCTAssertTrue(app.staticTexts["installedBuild"].label.contains("0.7.0"))
        let feedback = app.buttons["prepareBetaFeedback"]
        for _ in 0..<4 where !feedback.isHittable { app.swipeUp() }
        feedback.tap()
        let preview = app.staticTexts["betaFeedbackPreview"]
        XCTAssertTrue(preview.label.contains("Mode: Demo"))
        XCTAssertTrue(preview.label.contains("No glucose readings, sensor identifiers, notes or photos are attached."))
        XCTAssertTrue(app.buttons["shareBetaFeedback"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Phase 6 feedback preview"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let notes = app.buttons["0.7.0 · Beta distribution"]
        for _ in 0..<4 where !notes.isHittable { app.swipeUp() }
        notes.tap()
        XCTAssertTrue(app.staticTexts["Installed build information, version history and a previewable feedback template."].exists)
    }

    func testJournalEntryEditingExportAndDemoIsolation() {
        let app = XCUIApplication(); app.launchArguments = ["--demo"]; app.launch()
        app.tabBars.buttons["History"].tap(); app.buttons["healthJournal"].tap()
        XCTAssertTrue(app.staticTexts["journalDemoBanner"].waitForExistence(timeout: 5))
        app.buttons["addJournalEntry"].tap(); app.buttons["Note"].tap()
        app.textViews.firstMatch.exists ? app.textViews.firstMatch.tap() : app.textFields["journalNoteText"].tap()
        app.typeText("Phase 5 journal note")
        app.buttons["saveJournalEntry"].tap()
        let note = app.buttons["journalEntry-note"]
        for _ in 0..<4 where !note.isHittable { app.swipeUp() }
        note.tap()
        XCTAssertTrue(app.staticTexts["journalDetailSummary"].label.contains("Phase 5 journal note"))
        app.buttons["editJournalEntry"].tap()
        app.buttons["saveJournalEntry"].tap()
        XCTAssertTrue(app.staticTexts["journalDetailSummary"].label.contains("Phase 5 journal note"))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let export = app.buttons["journalExport"]
        for _ in 0..<4 where !export.isHittable { app.swipeDown() }
        export.tap()
        let prepare = app.buttons["prepareJournalExport"]
        for _ in 0..<4 where !prepare.isHittable { app.swipeUp() }
        prepare.tap()
        XCTAssertTrue(app.staticTexts["journalExportStatus"].label.contains("1 journal entries, 0 glucose readings"))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        for _ in 0..<4 where !note.isHittable { app.swipeUp() }; note.tap()
        app.buttons["deleteJournalEntry"].tap(); app.buttons["Delete permanently"].tap()
        XCTAssertTrue(app.staticTexts["emptyJournal"].waitForExistence(timeout: 5))
        app.tabBars.buttons["Settings"].tap(); app.buttons["demoButton"].tap()
        app.tabBars.buttons["History"].tap()
        if app.buttons["healthJournal"].exists { app.buttons["healthJournal"].tap() }
        XCTAssertFalse(app.staticTexts["journalDemoBanner"].exists)
        XCTAssertTrue(app.staticTexts["emptyJournal"].exists)
    }

    func testJournalInsulinCountdownAndValidation() {
        let app = XCUIApplication(); app.launchArguments = ["--demo"]; app.launch()
        app.tabBars.buttons["History"].tap(); app.buttons["healthJournal"].tap()
        app.buttons["addJournalEntry"].tap(); app.buttons["Insulin"].tap()
        app.buttons["saveJournalEntry"].tap()
        XCTAssertTrue(app.staticTexts["journalFormError"].exists)
        app.textFields["journalInsulinName"].tap(); app.typeText("Demo insulin")
        app.textFields["journalField-units"].tap(); app.typeText("1.5")
        app.textFields["journalField-durationMinutes"].tap(); app.typeText("180")
        app.buttons["saveJournalEntry"].tap()
        let entry = app.buttons["journalEntry-insulin"]
        for _ in 0..<4 where !entry.isHittable { app.swipeUp() }
        entry.tap()
        XCTAssertTrue(app.staticTexts["insulinCountdown"].label.contains("remaining"))
        XCTAssertTrue(app.staticTexts["journalDetailSummary"].label.contains("1.5 units"))
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Phase 5 recorded insulin countdown"; screenshot.lifetime = .keepAlways; add(screenshot)
    }

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
