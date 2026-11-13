import XCTest
@testable import GlucoTrailProtocol

/// A simulated transport drives exactly the policy used by the CoreBluetooth adapter.
final class ConnectionSessionTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1000)
    private func sensor() throws -> SensorIdentity {
        try SensorIdentity(peripheralID: UUID(), serial: "22222ABCDE")
    }

    func testSimulatedConnectDiscoverDisconnectReconnect() throws {
        let identity = try sensor()
        var session = ConnectionSession()
        session.start(identity)
        XCTAssertEqual(session.begin(at: now), identity.peripheralID)
        XCTAssertNil(session.begin(at: now))
        XCTAssertTrue(session.connected(identity.peripheralID, at: now))
        session.discovered(identity.peripheralID, compatible: true)
        XCTAssertEqual(session.phase, .connectedUnauthenticated)
        XCTAssertFalse(session.timedOut(at: now.addingTimeInterval(100)))
        session.failed(identity.peripheralID, at: now)
        XCTAssertEqual(session.phase, .retryWaiting)
        XCTAssertNil(session.begin(at: now.addingTimeInterval(1)))
        XCTAssertEqual(session.begin(at: now.addingTimeInterval(2)), identity.peripheralID)
    }

    func testTimeoutAndBoundedExponentialRetries() throws {
        let identity = try sensor()
        var session = ConnectionSession()
        var clock = now
        session.start(identity)
        for delay in [2.0, 4, 8, 16, 32] {
            XCTAssertNotNil(session.begin(at: clock))
            XCTAssertFalse(session.timedOut(at: clock.addingTimeInterval(19)))
            XCTAssertTrue(session.timedOut(at: clock.addingTimeInterval(20)))
            session.failed(identity.peripheralID, at: clock)
            XCTAssertEqual(session.retryAt, clock.addingTimeInterval(delay))
            clock = clock.addingTimeInterval(delay)
        }
        XCTAssertNotNil(session.begin(at: clock))
        session.failed(identity.peripheralID, at: clock)
        XCTAssertEqual(session.phase, .exhausted)
        XCTAssertNil(session.begin(at: clock.addingTimeInterval(600)))
        session.start(identity)
        XCTAssertEqual(session.failures, 0)
        XCTAssertNotNil(session.begin(at: clock))
    }

    func testStopRejectsLateCallbacksAndRetry() throws {
        let identity = try sensor()
        var session = ConnectionSession()
        session.start(identity)
        _ = session.begin(at: now)
        session.stop()
        XCTAssertFalse(session.connected(identity.peripheralID, at: now))
        session.failed(identity.peripheralID, at: now)
        session.discovered(identity.peripheralID, compatible: true)
        XCTAssertEqual(session.phase, .stopped)
        XCTAssertNil(session.begin(at: now.addingTimeInterval(600)))
    }

    func testPowerOrForegroundLossSuspendsAndResumesSameIdentity() throws {
        let identity = try sensor()
        var session = ConnectionSession()
        session.start(identity)
        _ = session.begin(at: now)
        session.suspend()
        XCTAssertNil(session.deadline)
        XCTAssertFalse(session.connected(identity.peripheralID, at: now))
        session.failed(identity.peripheralID, at: now)
        XCTAssertEqual(session.failures, 0)
        XCTAssertEqual(session.begin(at: now), identity.peripheralID)
    }

    func testSensorSwitchRejectsPreviousSensorEvents() throws {
        let old = try sensor(), replacement = try sensor()
        var session = ConnectionSession()
        session.start(old)
        _ = session.begin(at: now)
        session.start(replacement)
        _ = session.begin(at: now)
        XCTAssertFalse(session.connected(old.peripheralID, at: now))
        session.failed(old.peripheralID, at: now)
        XCTAssertEqual(session.phase, .connecting)
        XCTAssertTrue(session.connected(replacement.peripheralID, at: now))
    }

    func testMissingProfileDoesNotRetryOrBecomeStreaming() throws {
        let identity = try sensor()
        var session = ConnectionSession()
        session.start(identity)
        _ = session.begin(at: now)
        _ = session.connected(identity.peripheralID, at: now)
        XCTAssertTrue(session.timedOut(at: now.addingTimeInterval(15)))
        session.discovered(identity.peripheralID, compatible: false)
        session.failed(identity.peripheralID, at: now)
        XCTAssertEqual(session.phase, .unsupported)
        XCTAssertNil(session.begin(at: now.addingTimeInterval(600)))
    }
}
