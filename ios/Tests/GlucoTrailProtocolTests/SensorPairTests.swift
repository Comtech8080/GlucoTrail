import XCTest
@testable import GlucoTrailProtocol

final class SensorPairTests: XCTestCase {
    private let time = Date(timeIntervalSince1970: 1_700_000_000)
    private func identity(_ serial: String) throws -> SensorIdentity { try SensorIdentity(peripheralID: UUID(), serial: serial) }
    private func record(_ identity: SensorIdentity, _ minute: Int, at offset: Double, glucose: Int = 120, quality: Int = 100) -> GlucoseRecord {
        GlucoseRecord(identity: identity, reading: SensorReading(minute: minute, glucoseMgDl: glucose, trendRaw: 0, quality: quality,
            previousMgDl: nil, previous2MgDl: nil), receivedAt: time.addingTimeInterval(offset))
    }
    private func monitor() throws -> (SensorPairMonitor, SensorIdentity, SensorIdentity) {
        let a = try identity("22222AAAAA"), b = try identity("22222BBBBB")
        var value = SensorPairMonitor(state: SensorPairState(primary: a, replacement: b))
        value.beginMonitoring()
        return (value, a, b)
    }
    private func makeReady(_ value: inout SensorPairMonitor, _ identity: SensorIdentity, endingAt end: Double = 120, startingMinute: Int = 60) {
        for i in 0..<3 { value.accept(record(identity, startingMinute + i, at: end - Double(2 - i) * 60)) }
    }

    func testReplacementRequiresDifferentUUIDAndSerialAndPrimary() throws {
        var empty = SensorPairMonitor()
        let a = try identity("22222AAAAA")
        XCTAssertThrowsError(try empty.selectReplacement(a, at: time))
        empty.selectPrimary(a, at: time)
        XCTAssertThrowsError(try empty.selectReplacement(a, at: time))
        XCTAssertThrowsError(try empty.selectReplacement(identity("22222AAAAA"), at: time))
        let sameUUID = try SensorIdentity(peripheralID: a.peripheralID, serial: "22222BBBBB")
        XCTAssertThrowsError(try empty.selectReplacement(sameUUID, at: time))
    }

    func testManualPromotionRequiresThreeNewMinutesAndPreservesTransition() throws {
        var (value, a, b) = try monitor()
        value.accept(record(b, 60, at: 0))
        value.accept(record(b, 61, at: 60))
        XCTAssertThrowsError(try value.promote(at: time.addingTimeInterval(60)))
        value.accept(record(b, 62, at: 120))
        XCTAssertTrue(value.replacementReady(at: time.addingTimeInterval(120)))
        try value.promote(at: time.addingTimeInterval(120))
        XCTAssertEqual(value.state.primary, b)
        XCTAssertNil(value.state.replacement)
        XCTAssertEqual(value.state.transitions.last?.from, a)
        XCTAssertEqual(value.state.transitions.last?.to, b)
        XCTAssertEqual(value.state.transitions.last?.kind, .manualHandover)
        XCTAssertFalse(value.automatic)
        XCTAssertThrowsError(try value.promote(at: time.addingTimeInterval(121)))
    }

    func testDuplicatesOlderAndUnselectedReadingsCannotBuildReadiness() throws {
        var (value, _, b) = try monitor()
        let unrelated = try identity("22222CCCCC")
        for offset in [0.0, 60, 120] { value.accept(record(b, 60, at: offset)) }
        value.accept(record(b, 59, at: 121))
        makeReady(&value, unrelated)
        XCTAssertFalse(value.replacementReady(at: time.addingTimeInterval(121)))
    }

    func testWarmupMissingMinutesAndLongGapsCannotBuildReadiness() throws {
        var (value, _, b) = try monitor()
        makeReady(&value, b, startingMinute: 57)
        XCTAssertFalse(value.replacementReady(at: time.addingTimeInterval(120)))
        for (minute, offset) in [(60, 180.0), (62, 240.0), (63, 400.0)] { value.accept(record(b, minute, at: offset)) }
        XCTAssertFalse(value.replacementReady(at: time.addingTimeInterval(400)))
        value.accept(record(b, 64, at: 460))
        value.accept(record(b, 65, at: 520))
        XCTAssertTrue(value.replacementReady(at: time.addingTimeInterval(520)))
    }

    func testStaleFutureAndRejectedReplacementCannotPromote() throws {
        var (value, _, b) = try monitor()
        makeReady(&value, b)
        XCTAssertTrue(value.replacementReady(at: time.addingTimeInterval(239)))
        XCTAssertFalse(value.replacementReady(at: time.addingTimeInterval(240)))
        XCTAssertFalse(value.replacementReady(at: time.addingTimeInterval(119)))
        value.reject(b)
        XCTAssertFalse(value.replacementReady(at: time.addingTimeInterval(120)))
        value.accept(record(b, 62, at: 121))
        XCTAssertFalse(value.replacementReady(at: time.addingTimeInterval(121)))
    }

    func testAutomaticOffByDefaultAndRequiresPrimaryEvidence() throws {
        var (value, _, b) = try monitor()
        makeReady(&value, b, endingAt: 900)
        XCTAssertFalse(value.automatic)
        XCTAssertFalse(value.shouldAutomaticallyPromote(at: time.addingTimeInterval(900)))
        value.setAutomatic(true)
        XCTAssertFalse(value.shouldAutomaticallyPromote(at: time.addingTimeInterval(900)))
        XCTAssertThrowsError(try value.promote(at: time.addingTimeInterval(900), automatically: true))
    }

    func testAutomaticAfterFifteenMinutesWithoutPrimaryData() throws {
        var (value, a, b) = try monitor()
        value.accept(record(a, 100, at: 0))
        makeReady(&value, b, endingAt: 890)
        value.setAutomatic(true)
        XCTAssertFalse(value.shouldAutomaticallyPromote(at: time.addingTimeInterval(899)))
        XCTAssertTrue(value.shouldAutomaticallyPromote(at: time.addingTimeInterval(900)))
        try value.promote(at: time.addingTimeInterval(900), automatically: true)
        XCTAssertEqual(value.state.primary, b)
        XCTAssertEqual(value.state.transitions.last?.kind, .automaticHandover)
        XCTAssertFalse(value.automatic)
    }

    func testRecoveryOfPrimaryBlocksAutomaticHandover() throws {
        var (value, a, b) = try monitor()
        value.accept(record(a, 100, at: 0))
        makeReady(&value, b, endingAt: 900)
        value.setAutomatic(true)
        value.accept(record(a, 101, at: 899))
        XCTAssertFalse(value.shouldAutomaticallyPromote(at: time.addingTimeInterval(900)))
        XCTAssertEqual(value.state.primary, a)
    }

    func testStopResumeAndClockJumpsDisarmAndDiscardReadiness() throws {
        for jump in [-1.0, 91.0] {
            var (value, _, b) = try monitor()
            makeReady(&value, b)
            value.setAutomatic(true)
            value.pulse(at: time.addingTimeInterval(120))
            value.pulse(at: time.addingTimeInterval(120 + jump))
            XCTAssertFalse(value.automatic)
            XCTAssertFalse(value.replacementReady(at: time.addingTimeInterval(120)))
        }
        var (value, _, b) = try monitor()
        makeReady(&value, b)
        value.setAutomatic(true)
        value.suspend(); value.beginMonitoring()
        makeReady(&value, b)
        XCTAssertFalse(value.automatic)
        XCTAssertFalse(value.replacementReady(at: time.addingTimeInterval(120)))
        makeReady(&value, b, endingAt: 300, startingMinute: 63)
        XCTAssertTrue(value.replacementReady(at: time.addingTimeInterval(300)))
    }

    func testRemovingOrChangingReplacementDisarmsAndRejectsOldCallbacks() throws {
        var (value, a, b) = try monitor()
        makeReady(&value, b)
        value.setAutomatic(true)
        value.removeReplacement(at: time)
        XCTAssertFalse(value.automatic)
        XCTAssertEqual(value.state.primary, a)
        XCTAssertFalse(value.replacementReady(at: time))
        let c = try identity("22222CCCCC")
        try value.selectReplacement(c, at: time)
        makeReady(&value, b, endingAt: 300, startingMinute: 63)
        XCTAssertFalse(value.replacementReady(at: time.addingTimeInterval(300)))
        makeReady(&value, c, endingAt: 300)
        XCTAssertTrue(value.replacementReady(at: time.addingTimeInterval(300)))
    }

    func testComparisonRejectsSameSensorStaleWarmupAndSeparatedTimes() throws {
        let (_, a, b) = try monitor()
        let primary = record(a, 100, at: 0)
        XCTAssertNil(SensorComparison.pair(primary: primary, replacement: primary, now: time))
        XCTAssertNil(SensorComparison.pair(primary: primary, replacement: record(b, 59, at: 0), now: time))
        XCTAssertNil(SensorComparison.pair(primary: primary, replacement: record(b, 100, at: 121), now: time.addingTimeInterval(121)))
        XCTAssertNil(SensorComparison.pair(primary: primary, replacement: record(b, 100, at: 0), now: time.addingTimeInterval(300)))
        XCTAssertNil(SensorComparison.pair(primary: primary, replacement: record(b, 100, at: 1), now: time))
    }

    func testComparisonAndReferenceNeverModifyRawValues() throws {
        let (_, a, b) = try monitor()
        let primary = record(a, 100, at: 0, glucose: 120), replacement = record(b, 200, at: 0, glucose: 138)
        let pair = try XCTUnwrap(SensorComparison.pair(primary: primary, replacement: replacement, now: time))
        XCTAssertEqual(pair.signedDifferenceMgDl, 18)
        let reference = try XCTUnwrap(pair.differences(fromReferenceMgDl: 130))
        XCTAssertEqual(reference.primary, -10)
        XCTAssertEqual(reference.replacement, 8)
        XCTAssertNil(pair.differences(fromReferenceMgDl: .nan))
        XCTAssertNil(pair.differences(fromReferenceMgDl: 801))
        XCTAssertEqual(primary.reading.glucoseMgDl, 120)
        XCTAssertEqual(replacement.reading.glucoseMgDl, 138)
    }

    func testRolesAndEventsPersistTogetherButEligibilityDoesNot() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SensorPairStore(url: folder.appendingPathComponent("pair.json"))
        XCTAssertNil(try store.load())
        var (value, _, b) = try monitor()
        makeReady(&value, b)
        try value.promote(at: time.addingTimeInterval(120))
        try store.save(value.state)
        let restored = try XCTUnwrap(store.load())
        XCTAssertEqual(restored, value.state)
        XCTAssertEqual(restored.transitions.last?.to, restored.primary)
        let newSession = SensorPairMonitor(state: restored)
        XCTAssertFalse(newSession.automatic)
        XCTAssertFalse(newSession.replacementReady(at: time.addingTimeInterval(120)))
        var invalid = restored; invalid.replacement = restored.primary
        XCTAssertThrowsError(try store.save(invalid))
        XCTAssertEqual(try store.load(), restored)
    }

    func testCorruptRolesAreNotErasedOrLoaded() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let bytes = Data("broken".utf8)
        try bytes.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertThrowsError(try SensorPairStore(url: url).load())
        XCTAssertEqual(try Data(contentsOf: url), bytes)
    }

    func testTwoConnectionPoliciesDoNotShareRetriesOrStopState() throws {
        let (_, a, b) = try monitor()
        var primary = ConnectionSession(), replacement = ConnectionSession()
        primary.start(a); replacement.start(b)
        XCTAssertEqual(primary.begin(at: time), a.peripheralID)
        XCTAssertEqual(replacement.begin(at: time), b.peripheralID)
        primary.failed(a.peripheralID, at: time)
        XCTAssertTrue(replacement.connected(b.peripheralID, at: time))
        replacement.discovered(b.peripheralID, compatible: true)
        XCTAssertEqual(primary.phase, .retryWaiting)
        XCTAssertEqual(replacement.phase, .connectedUnauthenticated)
        primary.stop()
        XCTAssertEqual(replacement.phase, .connectedUnauthenticated)
    }
}
