import XCTest

final class GlucoTrailUITests: XCTestCase {
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
