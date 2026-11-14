import Foundation

public enum GraphWindow: Int, CaseIterable, Identifiable {
    case two = 2, four = 4, six = 6, twelve = 12, day = 24, twoDays = 48, threeDays = 72
    public var id: Int { rawValue }
    public func start(endingAt end: Date) -> Date { end.addingTimeInterval(-Double(rawValue) * 3600) }
}

public extension SensorReading {
    /// Matches Android AidexModels: signed tenths of mg/dL/min and its arrow bands.
    var rateMgDlPerMinute: Double { Double(trendRaw) / 10 }
    var trendArrow: String {
        switch trendRaw {
        case 30...: return "⇈"
        case 20..<30: return "↑"
        case 10..<20: return "↗"
        case ...(-30): return "⇊"
        case -29 ... -20: return "↓"
        case -19 ... -10: return "↘"
        default: return "→"
        }
    }
    var trendDescription: String {
        switch trendRaw {
        case 30...: return "Rising rapidly"
        case 20..<30: return "Rising"
        case 10..<20: return "Rising slowly"
        case ...(-30): return "Falling rapidly"
        case -29 ... -20: return "Falling"
        case -19 ... -10: return "Falling slowly"
        default: return "Steady"
        }
    }
}

public enum ReadingFreshness: Equatable {
    case unavailable, historical, stale, recent
    public static func evaluate(record: GlucoseRecord?, receiving: Bool, validatedThisSession: Bool, now: Date) -> Self {
        guard let record else { return .unavailable }
        let age = now.timeIntervalSince(record.receivedAt)
        guard age >= 0, age < 300 else { return .stale }
        return receiving && validatedThisSession ? .recent : .historical
    }
}

public struct GraphPoint: Identifiable {
    public let record: GlucoseRecord
    public let segment: Int
    public var id: String { record.id }
}

public enum GlucoseGraph {
    /// No interpolation across missing sensor minutes, identity changes, clock
    /// reversals, or receipt gaps >=5 minutes. Keep single readings as visible dots.
    public static func points(_ records: [GlucoseRecord], window: GraphWindow, endingAt end: Date) -> [GraphPoint] {
        let start = window.start(endingAt: end)
        let ordered = records.filter { $0.receivedAt >= start && $0.receivedAt <= end }
            .sorted { $0.receivedAt == $1.receivedAt ? $0.reading.minute < $1.reading.minute : $0.receivedAt < $1.receivedAt }
        var segment = 0
        var previous: GlucoseRecord?
        return ordered.map { record in
            if let previous {
                let elapsed = record.receivedAt.timeIntervalSince(previous.receivedAt)
                if previous.identity != record.identity || record.reading.minute != previous.reading.minute + 1 || elapsed <= 0 || elapsed >= 300 {
                    segment += 1
                }
            }
            previous = record
            return GraphPoint(record: record, segment: segment)
        }
    }
}

/// Deterministic UI-only sample data. Never passed to the history database.
public enum DemoHistory {
    public static func records(endingAt end: Date) -> [GlucoseRecord] {
        let identity = try! SensorIdentity(peripheralID: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!, serial: "DEMO000001")
        return (0...4320).compactMap { minute in
            // A visible 20-minute reception gap in the latest two-hour window.
            guard !(4220...4240).contains(minute) else { return nil }
            let glucose = 112 + Int(25 * sin(Double(minute) / 85))
            let trend = Int(3 * cos(Double(minute) / 85))
            let reading = SensorReading(minute: minute, glucoseMgDl: glucose, trendRaw: trend, quality: 100, previousMgDl: nil, previous2MgDl: nil)
            return GlucoseRecord(identity: identity, reading: reading, receivedAt: end.addingTimeInterval(Double(minute - 4320) * 60))
        }
    }
}
