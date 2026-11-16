import Foundation

public enum PairError: Error { case sameSensor, noPrimary, notReady, invalidState }
public struct SensorTransition: Codable, Equatable, Identifiable {
    public enum Kind: String, Codable { case primarySelected, replacementSelected, replacementRemoved, manualHandover, automaticHandover, forgotten }
    public let id: UUID
    public let date: Date
    public let kind: Kind
    public let from: SensorIdentity?
    public let to: SensorIdentity?
    public init(date: Date, kind: Kind, from: SensorIdentity?, to: SensorIdentity?) {
        id = UUID(); self.date = date; self.kind = kind; self.from = from; self.to = to
    }
}

public struct SensorPairState: Codable, Equatable {
    public var primary: SensorIdentity?
    public var replacement: SensorIdentity?
    public var transitions: [SensorTransition]
    public init(primary: SensorIdentity? = nil, replacement: SensorIdentity? = nil, transitions: [SensorTransition] = []) {
        self.primary = primary; self.replacement = replacement; self.transitions = transitions
    }
    public func validate() throws {
        for identity in [primary, replacement].compactMap({ $0 }) {
            _ = try SensorIdentity(peripheralID: identity.peripheralID, serial: identity.serial)
        }
        if let replacement {
            guard let primary else { throw PairError.noPrimary }
            guard primary.peripheralID != replacement.peripheralID, primary.serial != replacement.serial else { throw PairError.sameSensor }
        }
        guard transitions.count <= 200, transitions.allSatisfy({ $0.date.timeIntervalSince1970.isFinite }) else { throw PairError.invalidState }
    }
    mutating func log(_ event: SensorTransition) { transitions = Array((transitions + [event]).suffix(200)) }
}

/// Roles and their last 200 transitions are committed together via atomic file replacement.
public final class SensorPairStore {
    private struct Envelope: Codable { let version: Int; let state: SensorPairState }
    private let url: URL
    public init(url: URL) { self.url = url }
    public func load() throws -> SensorPairState? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let value = try JSONDecoder().decode(Envelope.self, from: Data(contentsOf: url))
        guard value.version == 1 else { throw PairError.invalidState }
        try value.state.validate()
        return value.state
    }
    public func save(_ state: SensorPairState) throws {
        try state.validate()
        try JSONEncoder().encode(Envelope(version: 1, state: state)).write(to: url, options: .atomic)
    }
}

/// Foreground-only policy, separate from Bluetooth connection state. Inputs must be
/// newly persisted, CRC-validated advertisements; replayed minutes never count.
public struct SensorPairMonitor {
    public private(set) var state: SensorPairState
    public private(set) var automatic = false
    public private(set) var monitoring = false
    private var latest: [SensorIdentity: GlucoseRecord] = [:]
    private var streak: [SensorIdentity: Int] = [:]
    private var highWater: [SensorIdentity: Int] = [:]
    private var lastPulse: Date?
    public init(state: SensorPairState = SensorPairState()) { self.state = state }

    public mutating func beginMonitoring() { resetEvidence(); monitoring = true }
    public mutating func suspend() { resetEvidence(); monitoring = false }
    private mutating func resetEvidence() { latest = [:]; streak = [:]; automatic = false; lastPulse = nil }
    /// Called every foreground tick. Clock jumps or long scheduling gaps disarm
    /// automatic switching rather than interpreting them as sensor silence.
    public mutating func pulse(at now: Date) {
        if let previous = lastPulse {
            let gap = now.timeIntervalSince(previous)
            if gap < 0 || gap > 90 { resetEvidence() }
        }
        lastPulse = now
    }
    public mutating func setAutomatic(_ enabled: Bool) {
        automatic = enabled && monitoring && state.primary != nil && state.replacement != nil
    }
    public mutating func selectPrimary(_ identity: SensorIdentity, at date: Date) {
        let previous = state.primary
        state.primary = identity; state.replacement = nil
        state.log(SensorTransition(date: date, kind: .primarySelected, from: previous, to: identity))
        resetEvidence()
    }
    public mutating func selectReplacement(_ identity: SensorIdentity, at date: Date) throws {
        guard let primary = state.primary else { throw PairError.noPrimary }
        guard identity.peripheralID != primary.peripheralID, identity.serial != primary.serial else { throw PairError.sameSensor }
        let previous = state.replacement
        state.replacement = identity
        state.log(SensorTransition(date: date, kind: .replacementSelected, from: previous, to: identity))
        latest[identity] = nil; streak[identity] = nil; automatic = false
    }
    public mutating func removeReplacement(at date: Date) {
        state.log(SensorTransition(date: date, kind: .replacementRemoved, from: state.replacement, to: nil))
        if let identity = state.replacement { latest[identity] = nil; streak[identity] = nil }
        state.replacement = nil; automatic = false
    }
    public mutating func forget(at date: Date) {
        state.log(SensorTransition(date: date, kind: .forgotten, from: state.primary, to: nil))
        state.primary = nil; state.replacement = nil
        suspend()
    }
    public mutating func reject(_ identity: SensorIdentity) {
        // Keep the primary's last accepted time for the loss-of-readings timer.
        streak[identity] = 0
        if identity == state.replacement { latest[identity] = nil }
    }
    public mutating func accept(_ record: GlucoseRecord) {
        let identity = record.identity, reading = record.reading
        guard monitoring, identity == state.primary || identity == state.replacement,
              reading.minute > (highWater[identity] ?? -1),
              record.receivedAt.timeIntervalSince1970.isFinite,
              (0...65535).contains(reading.minute), (18...800).contains(reading.glucoseMgDl),
              (1...255).contains(reading.quality), (-128...127).contains(reading.trendRaw) else { return }
        highWater[identity] = reading.minute
        let previous = latest[identity]
        let delta = previous.map { record.receivedAt.timeIntervalSince($0.receivedAt) } ?? -1
        let consecutive = previous.map { reading.minute == $0.reading.minute + 1 } ?? false
        streak[identity] = reading.minute >= 60 ? (consecutive && delta > 0 && delta <= 90 ? min(3, (streak[identity] ?? 0) + 1) : 1) : 0
        latest[identity] = record
    }
    public func replacementReady(at now: Date) -> Bool {
        guard monitoring, let identity = state.replacement, let record = latest[identity],
              (streak[identity] ?? 0) >= 3 else { return false }
        let age = now.timeIntervalSince(record.receivedAt)
        return age >= 0 && age < 120
    }
    public func shouldAutomaticallyPromote(at now: Date) -> Bool {
        guard automatic, replacementReady(at: now), let primary = state.primary,
              let record = latest[primary] else { return false }
        // Requires evidence of primary reception in this uninterrupted foreground session.
        return now.timeIntervalSince(record.receivedAt) >= 15 * 60
    }
    public mutating func promote(at now: Date, automatically: Bool = false) throws {
        guard replacementReady(at: now), !automatically || shouldAutomaticallyPromote(at: now),
              let replacement = state.replacement else { throw PairError.notReady }
        state.log(SensorTransition(date: now, kind: automatically ? .automaticHandover : .manualHandover,
            from: state.primary, to: replacement))
        state.primary = replacement; state.replacement = nil
        automatic = false
        // Preserve the new primary's accepted data; old callbacks are no longer eligible.
        latest = latest.filter { $0.key == replacement }; streak = streak.filter { $0.key == replacement }
    }
}

public struct SensorComparison {
    public let primary: GlucoseRecord
    public let replacement: GlucoseRecord
    public var signedDifferenceMgDl: Int { replacement.reading.glucoseMgDl - primary.reading.glucoseMgDl }
    public static func pair(primary: GlucoseRecord?, replacement: GlucoseRecord?, now: Date) -> SensorComparison? {
        guard let primary, let replacement,
              primary.identity.peripheralID != replacement.identity.peripheralID,
              primary.identity.serial != replacement.identity.serial else { return nil }
        for record in [primary, replacement] {
            let age = now.timeIntervalSince(record.receivedAt)
            guard age >= 0, age < 300, record.reading.minute >= 60,
                  (18...800).contains(record.reading.glucoseMgDl), record.reading.quality > 0 else { return nil }
        }
        guard abs(primary.receivedAt.timeIntervalSince(replacement.receivedAt)) <= 120 else { return nil }
        return SensorComparison(primary: primary, replacement: replacement)
    }
    /// Observational comparison only. Never writes calibration or changes raw readings.
    public func differences(fromReferenceMgDl reference: Double) -> (primary: Double, replacement: Double)? {
        guard reference.isFinite, (18...800).contains(reference) else { return nil }
        return (Double(primary.reading.glucoseMgDl) - reference, Double(replacement.reading.glucoseMgDl) - reference)
    }
}

public extension DemoHistory {
    static func replacementRecords(from primary: [GlucoseRecord]) -> [GlucoseRecord] {
        let identity = try! SensorIdentity(peripheralID: UUID(uuidString: "00000000-0000-0000-0000-000000000002")!, serial: "DEMO000002")
        return primary.map {
            let reading = SensorReading(minute: $0.reading.minute, glucoseMgDl: $0.reading.glucoseMgDl + 8,
                trendRaw: $0.reading.trendRaw, quality: 100, previousMgDl: nil, previous2MgDl: nil)
            return GlucoseRecord(identity: identity, reading: reading, receivedAt: $0.receivedAt)
        }
    }
}
