import XCTest
@testable import GlucoTrailProtocol

final class AlertPolicyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)
    private func identity(_ serial: String = "22222AAAAA") throws -> SensorIdentity { try SensorIdentity(peripheralID: UUID(), serial: serial) }
    private func settings() -> AlertSettings {
        var settings = AlertSettings(); settings.enabled = true
        settings.lowEnabled = true; settings.highEnabled = true
        settings.staleEnabled = true; settings.disconnectedEnabled = true
        return settings
    }
    private func record(_ id: SensorIdentity, glucose: Int = 200, minute: Int = 60, offset: Double = 0, quality: Int = 100) -> GlucoseRecord {
        GlucoseRecord(identity: id, reading: SensorReading(minute: minute, glucoseMgDl: glucose, trendRaw: 0,
            quality: quality, previousMgDl: nil, previous2MgDl: nil), receivedAt: now.addingTimeInterval(offset))
    }
    private func session() throws -> (AlertPolicy, SensorIdentity) {
        let id = try identity(); var policy = AlertPolicy(settings: settings())
        policy.start(primary: id, at: now); return (policy, id)
    }
    private func glucosePlans(_ policy: AlertPolicy, at time: Date? = nil) -> [AlertPlan] {
        policy.plans(at: time ?? now).filter { $0.kind == .low || $0.kind == .high }
    }

    func testAlertsAndEachCategoryDefaultOff() throws {
        let settings = AlertSettings()
        XCTAssertFalse(settings.enabled); XCTAssertFalse(settings.lowEnabled); XCTAssertFalse(settings.highEnabled)
        XCTAssertFalse(settings.staleEnabled); XCTAssertFalse(settings.disconnectedEnabled)
        let id = try identity(); var policy = AlertPolicy()
        policy.start(primary: id, at: now); policy.accept(record(id), now: now)
        XCTAssertTrue(policy.plans(at: now).isEmpty)
    }
    func testSettingsRejectInvalidThresholdsAndDurations() throws {
        for field in 0..<5 {
            var value = settings()
            switch field {
            case 0: value.lowMgDl = value.highMgDl
            case 1: value.highMgDl = 801
            case 2: value.staleMinutes = 0
            case 3: value.disconnectedMinutes = 31
            default: value.repeatMinutes = 1
            }
            XCTAssertThrowsError(try value.validate())
        }
    }
    func testInclusiveLowAndHighThresholds() throws {
        for (glucose, kind) in [(70, AlertKind.low), (180, .high)] {
            var (policy, id) = try session(); policy.accept(record(id, glucose: glucose), now: now)
            XCTAssertEqual(glucosePlans(policy).map(\.kind), [kind])
        }
        var (policy, id) = try session(); policy.accept(record(id, glucose: 100), now: now)
        XCTAssertTrue(glucosePlans(policy).isEmpty)
    }
    func testReplacementAndUnselectedDataCannotAlertOrRefreshStaleDeadline() throws {
        var (policy, _) = try session()
        policy.accept(record(try identity("22222BBBBB"), offset: 60), now: now.addingTimeInterval(60))
        XCTAssertTrue(glucosePlans(policy).isEmpty)
        XCTAssertEqual(policy.plans(at: now).first { $0.kind == .stale }?.due, now.addingTimeInterval(300))
    }
    func testWarmupInvalidAndFutureReceiptsAreRejected() throws {
        for (minute, glucose, quality, offset) in [(59, 200, 100, 0.0), (60, 801, 100, 0), (60, 200, 0, 0), (60, 200, 100, 1)] {
            var (policy, id) = try session()
            policy.accept(record(id, glucose: glucose, minute: minute, offset: offset, quality: quality), now: now)
            XCTAssertTrue(glucosePlans(policy).isEmpty)
        }
    }
    func testOldHistoryAndTwoMinuteDelayedDataCannotAlert() throws {
        var (policy, id) = try session()
        policy.accept(record(id, offset: -1), now: now)
        XCTAssertTrue(glucosePlans(policy).isEmpty)
        policy.accept(record(id), now: now.addingTimeInterval(120))
        XCTAssertTrue(glucosePlans(policy, at: now.addingTimeInterval(120)).isEmpty)
    }
    func testDuplicateAndOlderPacketsKeepOriginalDeadline() throws {
        var (policy, id) = try session(); policy.accept(record(id), now: now)
        let original = policy.plans(at: now)
        policy.accept(record(id, offset: 60), now: now.addingTimeInterval(60))
        policy.accept(record(id, minute: 59, offset: 61), now: now.addingTimeInterval(61))
        XCTAssertEqual(policy.plans(at: now), original)
    }
    func testNewMinuteRefreshesStaleReminderAndCancelsOldIdentifier() throws {
        var (policy, id) = try session()
        let original = try XCTUnwrap(policy.plans(at: now).first { $0.kind == .stale })
        policy.accept(record(id, glucose: 100, offset: 60), now: now.addingTimeInterval(60))
        let refreshed = try XCTUnwrap(policy.plans(at: now).first { $0.kind == .stale })
        XCTAssertNotEqual(original.id, refreshed.id)
        XCTAssertEqual(refreshed.due, now.addingTimeInterval(360))
    }
    func testGlucoseRequestExpiresBeforeDelayedDelivery() throws {
        var (policy, id) = try session(); policy.accept(record(id), now: now)
        XCTAssertEqual(glucosePlans(policy, at: now.addingTimeInterval(119)).count, 1)
        XCTAssertTrue(glucosePlans(policy, at: now.addingTimeInterval(120)).isEmpty)
    }
    func testCooldownRequiresNewDataAndNormalRecoveryCancelsPendingGlucose() throws {
        var (policy, id) = try session(); policy.accept(record(id), now: now)
        let first = glucosePlans(policy).first?.id
        policy.accept(record(id, minute: 61, offset: 60), now: now.addingTimeInterval(60))
        XCTAssertEqual(glucosePlans(policy).first?.id, first)
        policy.accept(record(id, glucose: 100, minute: 62, offset: 90), now: now.addingTimeInterval(90))
        XCTAssertTrue(glucosePlans(policy).isEmpty)
        policy.accept(record(id, minute: 63, offset: 899), now: now.addingTimeInterval(899))
        XCTAssertTrue(glucosePlans(policy, at: now.addingTimeInterval(899)).isEmpty)
        policy.accept(record(id, minute: 64, offset: 900), now: now.addingTimeInterval(900))
        XCTAssertEqual(glucosePlans(policy, at: now.addingTimeInterval(900)).count, 1)
        XCTAssertNotEqual(glucosePlans(policy, at: now.addingTimeInterval(900)).first?.id, first)
    }
    func testOppositeThresholdHasIndependentCooldown() throws {
        var (policy, id) = try session(); policy.accept(record(id), now: now)
        policy.accept(record(id, glucose: 60, minute: 61, offset: 60), now: now.addingTimeInterval(60))
        XCTAssertEqual(glucosePlans(policy, at: now.addingTimeInterval(60)).map(\.kind), [.low])
    }
    func testDisconnectDeadlineDoesNotMoveOnRepeatedCallbacksAndRecoveryCancelsIt() throws {
        var (policy, _) = try session()
        let original = policy.plans(at: now).first { $0.kind == .disconnected }
        policy.connection(false, at: now.addingTimeInterval(60))
        XCTAssertEqual(policy.plans(at: now).first { $0.kind == .disconnected }, original)
        policy.connection(true, at: now.addingTimeInterval(90))
        XCTAssertFalse(policy.plans(at: now).contains { $0.kind == .disconnected })
        policy.connection(false, at: now.addingTimeInterval(100))
        let next = policy.plans(at: now).first { $0.kind == .disconnected }
        XCTAssertNotEqual(next?.id, original?.id)
        XCTAssertEqual(next?.due, now.addingTimeInterval(400))
    }
    func testMissingReadingsPlanExistsWithoutAnyGlucoseAndDoesNotInventValue() throws {
        let (policy, _) = try session()
        let stale = try XCTUnwrap(policy.plans(at: now).first { $0.kind == .stale })
        XCTAssertNil(stale.glucoseMgDl)
        XCTAssertEqual(stale.due, now.addingTimeInterval(300))
        XCTAssertEqual(policy.plans(at: now.addingTimeInterval(600)).first { $0.kind == .stale }, stale)
    }
    func testBackgroundInvalidatesGlucoseButKeepsPreScheduledWarnings() throws {
        var (policy, id) = try session(); policy.accept(record(id), now: now)
        let warnings = policy.plans(at: now).filter { $0.kind == .stale || $0.kind == .disconnected }
        policy.invalidateGlucose()
        XCTAssertEqual(policy.plans(at: now), warnings)
    }
    func testStopDisableAndPrimaryChangeRemoveOldPlans() throws {
        var (policy, id) = try session(); policy.accept(record(id), now: now)
        let ids = Set(policy.plans(at: now).map(\.id))
        policy.stop(); XCTAssertTrue(policy.plans(at: now).isEmpty)
        policy.start(primary: try identity("22222BBBBB"), at: now)
        XCTAssertTrue(ids.isDisjoint(with: Set(policy.plans(at: now).map(\.id))))
        policy.accept(record(id), now: now); XCTAssertTrue(glucosePlans(policy).isEmpty)
        try policy.configure(AlertSettings()); XCTAssertTrue(policy.plans(at: now).isEmpty)
    }
    func testStopResumeDoesNotAcceptReplayedMinute() throws {
        var (policy, id) = try session(); policy.accept(record(id), now: now)
        policy.stop(); policy.start(primary: id, at: now.addingTimeInterval(60))
        policy.accept(record(id, offset: 60), now: now.addingTimeInterval(60))
        XCTAssertTrue(glucosePlans(policy, at: now.addingTimeInterval(60)).isEmpty)
    }
    func testClockJumpOrSuspensionDropsThresholdEvidenceAndRestartsGrace() throws {
        for offset in [-1.0, 91] {
            var (policy, id) = try session(); policy.pulse(at: now); policy.accept(record(id), now: now)
            let resumed = now.addingTimeInterval(offset); policy.pulse(at: resumed)
            XCTAssertTrue(glucosePlans(policy, at: resumed).isEmpty)
            XCTAssertEqual(policy.plans(at: resumed).first { $0.kind == .stale }?.due, resumed.addingTimeInterval(300))
        }
    }
    func testSettingsAndHistoryPersistButRuntimeMonitoringDoesNot() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let store = AlertStore(url: url); var archive = AlertArchive(); archive.settings = settings()
        archive.append(AlertHistoryEntry(requestID: "test", kind: .low, date: now, due: now, status: .acceptedBySystem))
        try store.save(archive); XCTAssertEqual(try store.load(), archive)
        let relaunched = AlertPolicy(settings: try store.load().settings)
        XCTAssertFalse(relaunched.running); XCTAssertTrue(relaunched.plans(at: now).isEmpty)
        XCTAssertFalse(archive.history.contains { $0.status == .observedDelivered })
    }
    func testCorruptArchiveIsNotErasedAndInvalidSavePreservesFile() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let store = AlertStore(url: url); let corrupt = Data("broken".utf8)
        try corrupt.write(to: url); XCTAssertThrowsError(try store.load()); XCTAssertEqual(try Data(contentsOf: url), corrupt)
        var invalid = AlertArchive(); invalid.settings.lowMgDl = 900
        XCTAssertThrowsError(try store.save(invalid)); XCTAssertEqual(try Data(contentsOf: url), corrupt)
    }
    func testHistoryIsBounded() {
        var archive = AlertArchive()
        for i in 0..<220 { archive.append(AlertHistoryEntry(requestID: "\(i)", kind: .stale, date: now, due: now, status: .requested)) }
        XCTAssertEqual(archive.history.count, 200); XCTAssertEqual(archive.history.first?.requestID, "20")
    }
    func testBackgroundRecoveryRequiresConsentRequestStorageAndSelectedUUID() {
        let id = UUID(), other = UUID()
        for enabled in [false, true] { for requested in [false, true] { for storage in [false, true] {
            XCTAssertEqual(BackgroundRecoveryPolicy.mayRecover(enabled: enabled, requested: requested,
                storageAvailable: storage, identity: id, selected: [id]), enabled && requested && storage)
        } } }
        XCTAssertFalse(BackgroundRecoveryPolicy.mayRecover(enabled: true, requested: true, storageAvailable: true, identity: other, selected: [id]))
        XCTAssertFalse(BackgroundRecoveryPolicy.mayReadGlucose(foreground: false, requested: true))
        XCTAssertFalse(BackgroundRecoveryPolicy.mayReadGlucose(foreground: true, requested: false))
        XCTAssertTrue(BackgroundRecoveryPolicy.mayReadGlucose(foreground: true, requested: true))
    }
    func testLateNotificationCallbacksRejectedAfterStopReplacementOrExpiry() throws {
        var (policy, id) = try session(); policy.accept(record(id), now: now)
        let plan = try XCTUnwrap(glucosePlans(policy).first)
        let desired = Dictionary(uniqueKeysWithValues: policy.plans(at: now).map { ($0.id, $0) })
        XCTAssertTrue(AlertRequestGate.permits(plan, desired: desired, at: now))
        XCTAssertFalse(AlertRequestGate.permits(plan, desired: [:], at: now))
        XCTAssertFalse(AlertRequestGate.permits(plan, desired: desired, at: now.addingTimeInterval(120)))
        XCTAssertFalse(AlertRequestGate.permits(plan, desired: desired, at: now.addingTimeInterval(-1)))
        policy.start(primary: try identity("22222BBBBB"), at: now)
        let replaced = Dictionary(uniqueKeysWithValues: policy.plans(at: now).map { ($0.id, $0) })
        XCTAssertFalse(AlertRequestGate.permits(plan, desired: replaced, at: now))
    }
}
