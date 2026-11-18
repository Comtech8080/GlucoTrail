import Foundation

public enum AlertKind: String, Codable, CaseIterable { case low, high, stale, disconnected }
public enum AlertPolicyError: Error { case invalidSettings, invalidArchive }

public struct AlertSettings: Codable, Equatable {
    public var enabled = false
    public var lowEnabled = false
    public var highEnabled = false
    public var staleEnabled = false
    public var disconnectedEnabled = false
    public var lowMgDl = 70
    public var highMgDl = 180
    public var staleMinutes = 5
    public var disconnectedMinutes = 5
    public var repeatMinutes = 15
    public init() {}
    public func validate() throws {
        guard (18...799).contains(lowMgDl), (19...800).contains(highMgDl), lowMgDl < highMgDl,
              (3...30).contains(staleMinutes), (1...30).contains(disconnectedMinutes),
              (5...60).contains(repeatMinutes) else { throw AlertPolicyError.invalidSettings }
    }
}

public struct AlertPlan: Equatable, Identifiable {
    public let id: String
    public let kind: AlertKind
    public let due: Date
    public let evidenceAt: Date
    public let glucoseMgDl: Int?
    public let expiresAt: Date?
}

/// Used on both sides of asynchronous notification requests. Removing/replacing
/// desired work invalidates callbacks from an older session or sensor role.
public enum AlertRequestGate {
    public static func permits(_ plan: AlertPlan, desired: [String: AlertPlan], at now: Date) -> Bool {
        desired[plan.id] == plan && now >= plan.evidenceAt && (plan.expiresAt.map { now < $0 } ?? true)
    }
}

/// Receives only newly persisted primary advertisements. Never evaluates restored
/// history, replacement values, or demo data. No timer is assumed to run suspended.
public struct AlertPolicy {
    public private(set) var settings: AlertSettings
    public private(set) var primary: SensorIdentity?
    public private(set) var running = false
    private var epoch = UUID().uuidString
    private var started: Date?
    private var lastReceipt: Date?
    private var disconnectedAt: Date?
    private var connectionEpoch = UUID().uuidString
    private var highWater: [SensorIdentity: Int] = [:]
    private var lastAlert: [AlertKind: Date] = [:]
    private var glucosePlan: AlertPlan?
    private var previousPulse: Date?
    public init(settings: AlertSettings = AlertSettings()) { self.settings = settings }

    public mutating func configure(_ value: AlertSettings) throws {
        try value.validate(); settings = value; stop()
    }
    public mutating func start(primary: SensorIdentity?, at now: Date) {
        stop(); self.primary = primary
        running = primary != nil && now.timeIntervalSince1970.isFinite
        started = running ? now : nil
        disconnectedAt = started
    }
    public mutating func stop() {
        running = false; primary = nil; started = nil; lastReceipt = nil
        disconnectedAt = nil; glucosePlan = nil; lastAlert = [:]; previousPulse = nil
        epoch = UUID().uuidString; connectionEpoch = UUID().uuidString
    }
    public mutating func connection(_ connected: Bool, at now: Date) {
        guard running else { return }
        if connected { disconnectedAt = nil }
        else if disconnectedAt == nil { disconnectedAt = now; connectionEpoch = UUID().uuidString }
    }
    /// Long gaps invalidate glucose evidence and start new warning grace periods.
    /// A scheduled warning can still be delivered by iOS while execution is absent.
    public mutating func pulse(at now: Date) {
        if let previous = previousPulse, now < previous || now.timeIntervalSince(previous) > 90 {
            let identity = primary, wasDisconnected = disconnectedAt != nil
            start(primary: identity, at: now)
            if !wasDisconnected { disconnectedAt = nil }
        }
        previousPulse = now
    }
    public mutating func invalidateGlucose() { glucosePlan = nil }
    public mutating func accept(_ record: GlucoseRecord, now: Date) {
        guard running, record.identity == primary,
              record.reading.minute > (highWater[record.identity] ?? -1) else { return }
        let age = now.timeIntervalSince(record.receivedAt)
        guard age >= 0, age < 120, record.receivedAt >= (started ?? .distantFuture),
              (60...65535).contains(record.reading.minute), (18...800).contains(record.reading.glucoseMgDl),
              (1...255).contains(record.reading.quality) else { glucosePlan = nil; return }
        highWater[record.identity] = record.reading.minute
        lastReceipt = record.receivedAt
        let glucose = record.reading.glucoseMgDl
        let kind: AlertKind? = glucose <= settings.lowMgDl && settings.lowEnabled ? .low
            : glucose >= settings.highMgDl && settings.highEnabled ? .high : nil
        guard settings.enabled, let kind else { glucosePlan = nil; return }
        if let previous = lastAlert[kind], now.timeIntervalSince(previous) < Double(settings.repeatMinutes * 60) {
            if glucosePlan?.kind != kind { glucosePlan = nil }
            return
        }
        lastAlert[kind] = now
        glucosePlan = AlertPlan(id: "glucotrail.alert.\(epoch).\(kind.rawValue).\(record.reading.minute)",
            kind: kind, due: now, evidenceAt: record.receivedAt, glucoseMgDl: glucose,
            expiresAt: record.receivedAt.addingTimeInterval(120))
    }
    public func plans(at now: Date) -> [AlertPlan] {
        guard running, settings.enabled, let started else { return [] }
        var result: [AlertPlan] = []
        if let plan = glucosePlan, now >= plan.evidenceAt, now < (plan.expiresAt ?? .distantPast) { result.append(plan) }
        if settings.staleEnabled {
            let anchor = lastReceipt ?? started
            result.append(AlertPlan(id: "glucotrail.alert.\(epoch).stale.\(anchor.timeIntervalSince1970)", kind: .stale,
                due: anchor.addingTimeInterval(Double(settings.staleMinutes * 60)), evidenceAt: anchor, glucoseMgDl: nil, expiresAt: nil))
        }
        if settings.disconnectedEnabled, let disconnectedAt {
            result.append(AlertPlan(id: "glucotrail.alert.\(epoch).disconnected.\(connectionEpoch)", kind: .disconnected,
                due: disconnectedAt.addingTimeInterval(Double(settings.disconnectedMinutes * 60)),
                evidenceAt: disconnectedAt, glucoseMgDl: nil, expiresAt: nil))
        }
        return result
    }
}

public struct AlertHistoryEntry: Codable, Equatable, Identifiable {
    public enum Status: String, Codable { case requested, acceptedBySystem, observedDelivered, failed, permissionDenied, cancelled }
    public let id: UUID
    public let requestID: String
    public let kind: AlertKind
    public let date: Date
    public let due: Date
    public let status: Status
    public init(requestID: String, kind: AlertKind, date: Date, due: Date, status: Status) {
        id = UUID(); self.requestID = requestID; self.kind = kind; self.date = date; self.due = due; self.status = status
    }
}

public struct AlertArchive: Codable, Equatable {
    public var settings = AlertSettings()
    public var history: [AlertHistoryEntry] = []
    public init() {}
    public mutating func append(_ event: AlertHistoryEntry) { history = Array((history + [event]).suffix(200)) }
}

public final class AlertStore {
    private struct Envelope: Codable { let version: Int; let archive: AlertArchive }
    private let url: URL
    public init(url: URL) { self.url = url }
    public func load() throws -> AlertArchive {
        guard FileManager.default.fileExists(atPath: url.path) else { return AlertArchive() }
        let envelope = try JSONDecoder().decode(Envelope.self, from: Data(contentsOf: url))
        guard envelope.version == 1 else { throw AlertPolicyError.invalidArchive }
        try validate(envelope.archive)
        return envelope.archive
    }
    public func save(_ archive: AlertArchive) throws {
        try validate(archive)
        try JSONEncoder().encode(Envelope(version: 1, archive: archive)).write(to: url, options: .atomic)
    }
    private func validate(_ archive: AlertArchive) throws {
        try archive.settings.validate()
        guard archive.history.count <= 200, archive.history.allSatisfy({
            $0.date.timeIntervalSince1970.isFinite && $0.due.timeIntervalSince1970.isFinite
        }) else { throw AlertPolicyError.invalidArchive }
    }
}

/// Recovery only: background advertisements are not a validated glucose stream.
public enum BackgroundRecoveryPolicy {
    public static func mayRecover(enabled: Bool, requested: Bool, storageAvailable: Bool,
                                  identity: UUID, selected: Set<UUID>) -> Bool {
        enabled && requested && storageAvailable && selected.contains(identity)
    }
    public static func mayReadGlucose(foreground: Bool, requested: Bool) -> Bool { foreground && requested }
}
