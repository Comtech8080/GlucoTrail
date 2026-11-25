import Foundation

/// Deterministic connection policy. The CoreBluetooth adapter owns timers and radio I/O.
/// All calls must be serialized (the app uses the main queue).
public struct ConnectionSession {
    public enum Phase: Equatable {
        case stopped, waiting, connecting, discovering, connectedUnauthenticated
        case retryWaiting, exhausted, unsupported
    }
    public private(set) var identity: SensorIdentity?
    public private(set) var phase: Phase = .stopped
    public private(set) var failures = 0
    public private(set) var requested = false
    public private(set) var deadline: Date?
    public private(set) var retryAt: Date?
    public init() {}

    public mutating func start(_ identity: SensorIdentity) {
        self.identity = identity
        requested = true
        failures = 0
        phase = .waiting
        deadline = nil
        retryAt = nil
    }
    public mutating func stop() {
        requested = false
        phase = .stopped
        deadline = nil
        retryAt = nil
    }
    public mutating func suspend() {
        guard requested else { return }
        phase = .waiting
        deadline = nil
        retryAt = nil
    }
    public mutating func begin(at now: Date) -> UUID? {
        guard requested, phase == .waiting || (phase == .retryWaiting && now >= (retryAt ?? .distantFuture)) else { return nil }
        phase = .connecting
        deadline = now.addingTimeInterval(20)
        retryAt = nil
        return identity?.peripheralID
    }
    public mutating func connected(_ id: UUID, at now: Date) -> Bool {
        guard identity?.peripheralID == id, phase == .connecting else { return false }
        phase = .discovering
        deadline = now.addingTimeInterval(15)
        return true
    }
    public mutating func discovered(_ id: UUID, compatible: Bool) {
        guard identity?.peripheralID == id, phase == .discovering else { return }
        phase = compatible ? .connectedUnauthenticated : .unsupported
        deadline = nil
        // Do not reset failures merely because a flapping radio briefly connects.
    }
    public mutating func failed(_ id: UUID, at now: Date) {
        guard requested, identity?.peripheralID == id,
              [.connecting, .discovering, .connectedUnauthenticated].contains(phase) else { return }
        failures += 1
        deadline = nil
        if failures > 5 {
            phase = .exhausted
            retryAt = nil
        } else {
            phase = .retryWaiting
            retryAt = now.addingTimeInterval(min(60, 2 * pow(2, Double(failures - 1))))
        }
    }
    public func timedOut(at now: Date) -> Bool { deadline.map { now >= $0 } ?? false }
}
