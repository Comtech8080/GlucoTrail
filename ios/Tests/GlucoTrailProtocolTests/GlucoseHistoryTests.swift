import XCTest
import CSQLite
@testable import GlucoTrailProtocol

final class GlucoseHistoryTests: XCTestCase {
    private var folder: URL!
    private var url: URL { folder.appendingPathComponent("history.sqlite") }
    private let time = Date(timeIntervalSince1970: 1_700_000_000)
    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: folder) }
    private func sensor(_ serial: String = "22222ABCDE", id: UUID = UUID()) throws -> SensorIdentity {
        try SensorIdentity(peripheralID: id, serial: serial)
    }
    private func reading(_ minute: Int, glucose: Int = 125) -> SensorReading {
        SensorReading(minute: minute, glucoseMgDl: glucose, trendRaw: -12, quality: 100, previousMgDl: nil, previous2MgDl: nil)
    }
    private func raw(_ sql: String) throws {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        XCTAssertEqual(sqlite3_exec(db, sql, nil, nil, nil), SQLITE_OK)
    }

    func testEmptyDatabaseAndRelaunchPersistence() throws {
        let identity = try sensor()
        var db: GlucoseHistory? = try GlucoseHistory(url: url)
        XCTAssertNil(try db!.latest(for: identity))
        XCTAssertTrue(try db!.identities().isEmpty)
        XCTAssertTrue(try db!.insert(reading(100), identity: identity, receivedAt: time))
        db = nil
        let relaunched = try GlucoseHistory(url: url)
        let result = try XCTUnwrap(relaunched.latest(for: identity))
        XCTAssertEqual(result.reading, reading(100))
        XCTAssertEqual(result.receivedAt, time)
        XCTAssertEqual(try relaunched.identities(), [identity])
        XCTAssertEqual(ReadingFreshness.evaluate(record: result, receiving: true, validatedThisSession: false, now: time), .historical)
    }

    func testDuplicateAndOlderMinutesNeverReplaceValueOrReceiptDate() throws {
        let identity = try sensor()
        var db: GlucoseHistory? = try GlucoseHistory(url: url)
        try db!.insert(reading(100), identity: identity, receivedAt: time)
        db = nil
        let restored = try GlucoseHistory(url: url)
        XCTAssertFalse(try restored.insert(reading(100, glucose: 300), identity: identity, receivedAt: time.addingTimeInterval(240)))
        XCTAssertFalse(try restored.insert(reading(99), identity: identity, receivedAt: time.addingTimeInterval(250)))
        XCTAssertEqual(try restored.latest(for: identity)?.reading.glucoseMgDl, 125)
        XCTAssertEqual(try restored.latest(for: identity)?.receivedAt, time)
        XCTAssertTrue(try restored.insert(reading(101), identity: identity, receivedAt: time.addingTimeInterval(60)))
    }

    func testSameSerialDifferentUUIDAndSameUUIDDifferentSerialAreIsolated() throws {
        let first = try sensor(), second = try sensor(), third = try sensor("22222ZZZZZ", id: first.peripheralID)
        let db = try GlucoseHistory(url: url)
        for (index, identity) in [first, second, third].enumerated() {
            XCTAssertTrue(try db.insert(reading(100, glucose: 100 + index), identity: identity, receivedAt: time))
        }
        XCTAssertEqual(try db.identities().count, 3)
        XCTAssertEqual(try db.latest(for: first)?.reading.glucoseMgDl, 100)
        XCTAssertEqual(try db.latest(for: second)?.reading.glucoseMgDl, 101)
        XCTAssertEqual(try db.latest(for: third)?.reading.glucoseMgDl, 102)
    }

    func testEveryGraphWindowHasInclusiveBoundariesAndNoFutureRecords() throws {
        let db = try GlucoseHistory(url: url), identity = try sensor()
        for minute in 0...72 {
            try db.insert(reading(minute), identity: identity, receivedAt: time.addingTimeInterval(Double(minute - 72) * 3600))
        }
        try db.insert(reading(73), identity: identity, receivedAt: time.addingTimeInterval(3600))
        for window in GraphWindow.allCases {
            let records = try db.records(for: identity, from: window.start(endingAt: time), through: time)
            XCTAssertEqual(records.count, window.rawValue + 1)
            XCTAssertEqual(records.first?.receivedAt, window.start(endingAt: time))
            XCTAssertEqual(records.last?.receivedAt, time)
        }
    }

    func testInvalidValuesAndInvalidDatesRejected() throws {
        let db = try GlucoseHistory(url: url), identity = try sensor()
        for value in [reading(-1), reading(65536), reading(1, glucose: 17), reading(1, glucose: 801)] {
            XCTAssertThrowsError(try db.insert(value, identity: identity, receivedAt: time))
        }
        XCTAssertThrowsError(try db.insert(reading(1), identity: identity, receivedAt: Date(timeIntervalSince1970: .infinity)))
        XCTAssertThrowsError(try db.records(for: identity, from: time.addingTimeInterval(1), through: time))
        XCTAssertNil(try db.latest(for: identity))
    }

    func testClockRollbackDoesNotChangeMinuteOrderingOrOverwriteHistory() throws {
        let db = try GlucoseHistory(url: url), identity = try sensor()
        try db.insert(reading(100), identity: identity, receivedAt: time)
        try db.insert(reading(101), identity: identity, receivedAt: time.addingTimeInterval(-60))
        XCTAssertEqual(try db.latest(for: identity)?.reading.minute, 101)
        let records = try db.records(for: identity, from: time.addingTimeInterval(-120), through: time)
        XCTAssertEqual(records.map { $0.reading.minute }, [101, 100])
        XCTAssertEqual(GlucoseGraph.points(records, window: .two, endingAt: time).map(\.segment), [0, 1])
    }

    func testFutureSchemaIsPreservedAndRejected() throws {
        try raw("PRAGMA user_version = 99")
        let before = try Data(contentsOf: url)
        XCTAssertThrowsError(try GlucoseHistory(url: url)) { error in
            XCTAssertEqual(error as? HistoryError, .unsupportedVersion(99))
        }
        XCTAssertEqual(try Data(contentsOf: url), before)
    }

    func testCorruptionIsNotReplacedWithAnEmptyDatabase() throws {
        let bytes = Data("not a SQLite database".utf8)
        try bytes.write(to: url)
        XCTAssertThrowsError(try GlucoseHistory(url: url))
        XCTAssertEqual(try Data(contentsOf: url), bytes)
    }

    func testFailedWriteCanRetryWithoutLosingPriorReading() throws {
        let db = try GlucoseHistory(url: url), identity = try sensor()
        try db.insert(reading(100), identity: identity, receivedAt: time)
        var blocker: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &blocker), SQLITE_OK)
        defer { sqlite3_close(blocker) }
        XCTAssertEqual(sqlite3_exec(blocker, "BEGIN EXCLUSIVE", nil, nil, nil), SQLITE_OK)
        XCTAssertThrowsError(try db.insert(reading(101), identity: identity, receivedAt: time.addingTimeInterval(60)))
        XCTAssertEqual(sqlite3_exec(blocker, "ROLLBACK", nil, nil, nil), SQLITE_OK)
        XCTAssertEqual(try db.latest(for: identity)?.reading.minute, 100)
        XCTAssertTrue(try db.insert(reading(101), identity: identity, receivedAt: time.addingTimeInterval(60)))
    }
}
