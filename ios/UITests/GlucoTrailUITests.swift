import XCTest

final class GlucoTrailUITests: XCTestCase {
    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func changeSwitch(_ toggle: XCUIElement) {
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        let before = toggle.value as? String
        // SwiftUI exposes the whole labelled row as the switch frame. Tap the
        // trailing switch control, rather than the inert label at the row centre.
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        let changed = NSPredicate { _, _ in (toggle.value as? String) != before }
        expectation(for: changed, evaluatedWith: toggle)
        waitForExpectations(timeout: 5)
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
        changeSwitch(app.switches["unitToggle"])
        app.tabBars.buttons["Dashboard"].tap()
        XCTAssertNotEqual(app.staticTexts["latestGlucose"].label, initialValue)
        app.tabBars.buttons["Settings"].tap()
        changeSwitch(app.switches["demoToggle"])
        app.tabBars.buttons["Dashboard"].tap()
        XCTAssertTrue(app.staticTexts["No glucose reading yet"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["demoBanner"].exists)
        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.staticTexts["No readings for this sensor and date."].waitForExistence(timeout: 5))
    }
}
