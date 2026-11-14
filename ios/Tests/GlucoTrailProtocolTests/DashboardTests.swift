import XCTest
@testable import GlucoTrailProtocol

final class DashboardTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)
    private func record(minute: Int = 100, age: Double = 0, trend: Int = 0) throws -> GlucoseRecord {
        let identity = try SensorIdentity(peripheralID: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!, serial: "22222ABCDE")
        let reading = SensorReading(minute: minute, glucoseMgDl: 180, trendRaw: trend, quality: 100, previousMgDl: nil, previous2MgDl: nil)
        return GlucoseRecord(identity: identity, reading: reading, receivedAt: now.addingTimeInterval(-age))
    }

    func testTrendBandsMatchAndroidIncludingSignedBoundaries() throws {
        let expected = [(-128, "⇊"), (-30, "⇊"), (-29, "↓"), (-20, "↓"), (-19, "↘"), (-10, "↘"), (-9, "→"), (0, "→"), (9, "→"), (10, "↗"), (19, "↗"), (20, "↑"), (29, "↑"), (30, "⇈"), (127, "⇈")]
        for (raw, arrow) in expected { XCTAssertEqual(try record(trend: raw).reading.trendArrow, arrow) }
        XCTAssertEqual(try record(trend: -12).reading.rateMgDlPerMinute, -1.2)
        XCTAssertEqual(try record().reading.mmolL, 10)
    }

    func testFreshnessRequiresNewSessionDataAndActiveReception() throws {
        let recent = try record(age: 299)
        XCTAssertEqual(ReadingFreshness.evaluate(record: recent, receiving: true, validatedThisSession: true, now: now), .recent)
        XCTAssertEqual(ReadingFreshness.evaluate(record: recent, receiving: false, validatedThisSession: true, now: now), .historical)
        XCTAssertEqual(ReadingFreshness.evaluate(record: recent, receiving: true, validatedThisSession: false, now: now), .historical)
        XCTAssertEqual(ReadingFreshness.evaluate(record: try record(age: 300), receiving: true, validatedThisSession: true, now: now), .stale)
        XCTAssertEqual(ReadingFreshness.evaluate(record: try record(age: -1), receiving: true, validatedThisSession: true, now: now), .stale)
        XCTAssertEqual(ReadingFreshness.evaluate(record: nil, receiving: true, validatedThisSession: true, now: now), .unavailable)
    }

    func testGapsAreNotBridgedOrInvented() throws {
        let input = try [record(minute: 100, age: 600), record(minute: 101, age: 540),
                         record(minute: 103, age: 420), record(minute: 104, age: 120), record(minute: 105, age: 60)]
        let points = GlucoseGraph.points(input, window: .two, endingAt: now)
        XCTAssertEqual(points.count, input.count)
        XCTAssertEqual(points.map(\.segment), [0, 0, 1, 2, 2])
    }

    func testAllSevenWindowsFilterOutsideAndFuturePoints() throws {
        XCTAssertEqual(GraphWindow.allCases.map(\.rawValue), [2, 4, 6, 12, 24, 48, 72])
        for window in GraphWindow.allCases {
            let age = Double(window.rawValue) * 3600
            let input = try [record(minute: 1, age: age + 1), record(minute: 2, age: age), record(minute: 3), record(minute: 4, age: -1)]
            XCTAssertEqual(GlucoseGraph.points(input, window: window, endingAt: now).map { $0.record.reading.minute }, [2, 3])
        }
    }

    func testDemoCovers72HoursWithExplicitGapAndOwnIdentity() {
        let demo = DemoHistory.records(endingAt: now)
        XCTAssertEqual(demo.first?.receivedAt, now.addingTimeInterval(-72 * 3600))
        XCTAssertEqual(demo.last?.receivedAt, now)
        XCTAssertTrue(demo.allSatisfy { $0.identity.serial == "DEMO000001" })
        XCTAssertEqual(demo.count, 4300)
        XCTAssertEqual(Set(GlucoseGraph.points(demo, window: .two, endingAt: now).map(\.segment)).count, 2)
    }
}
