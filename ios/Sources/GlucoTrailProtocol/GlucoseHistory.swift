import Foundation
import CSQLite

public struct GlucoseRecord: Identifiable, Equatable {
    public let identity: SensorIdentity
    public let reading: SensorReading
    /// Phone receipt time, NOT a reconstructed sensor measurement timestamp.
    public let receivedAt: Date
    public var id: String { "\(identity.peripheralID):\(identity.serial):\(reading.minute)" }
    public init(identity: SensorIdentity, reading: SensorReading, receivedAt: Date) {
        self.identity = identity; self.reading = reading; self.receivedAt = receivedAt
    }
}

public enum HistoryError: Error, Equatable {
    case database(String), unsupportedVersion(Int), invalidRecord, tooManyRecords
}

/// Local SQLite database. Call from one serial executor (the app's main queue).
/// Failures never delete or silently recreate an existing database.
public final class GlucoseHistory {
    private var db: OpaquePointer?
    private let columns = "peripheral_id, serial, sensor_minute, glucose, trend, quality, received_at"
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    public init(url: URL) throws {
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            let message = db.map { String(cString: sqlite3_errmsg($0)) } ?? "Cannot open history"
            sqlite3_close(db); db = nil
            throw HistoryError.database(message)
        }
        do {
            sqlite3_busy_timeout(db, 250)
            let statement = try prepare("PRAGMA user_version")
            let result = sqlite3_step(statement)
            let version = Int(sqlite3_column_int(statement, 0))
            sqlite3_finalize(statement)
            guard result == SQLITE_ROW else { throw failure() }
            guard version == 0 || version == 1 else { throw HistoryError.unsupportedVersion(version) }
            if version == 0 {
                try execute("BEGIN IMMEDIATE")
                do {
                    // CREATE without IF NOT EXISTS prevents adopting an unknown pre-versioned schema.
                    try execute("""
                        CREATE TABLE readings (
                          peripheral_id TEXT NOT NULL, serial TEXT NOT NULL,
                          sensor_minute INTEGER NOT NULL CHECK(sensor_minute BETWEEN 0 AND 65535),
                          glucose INTEGER NOT NULL CHECK(glucose BETWEEN 18 AND 800),
                          trend INTEGER NOT NULL CHECK(trend BETWEEN -128 AND 127),
                          quality INTEGER NOT NULL CHECK(quality BETWEEN 1 AND 255),
                          received_at REAL NOT NULL,
                          PRIMARY KEY(peripheral_id, serial, sensor_minute)
                        )
                        """)
                    try execute("CREATE INDEX readings_time ON readings(peripheral_id, serial, received_at)")
                    try execute("PRAGMA user_version = 1")
                    try execute("COMMIT")
                } catch { try? execute("ROLLBACK"); throw error }
            }
            // Validate the expected columns even if an existing file reports version 1.
            let check = try prepare("SELECT \(columns) FROM readings LIMIT 0")
            sqlite3_finalize(check)
        } catch {
            sqlite3_close(db); db = nil
            throw error
        }
    }

    deinit { sqlite3_close(db) }

    /// Use only after parser validation. Older and repeated sensor minutes never replace
    /// the original value/time, including after process restart or a clock change.
    @discardableResult
    public func insert(_ reading: SensorReading, identity: SensorIdentity, receivedAt: Date) throws -> Bool {
        guard (0...65535).contains(reading.minute), (18...800).contains(reading.glucoseMgDl),
              (-128...127).contains(reading.trendRaw), (1...255).contains(reading.quality),
              receivedAt.timeIntervalSince1970.isFinite else { throw HistoryError.invalidRecord }
        _ = try SensorIdentity(peripheralID: identity.peripheralID, serial: identity.serial)
        let s = try prepare("""
            INSERT INTO readings (\(columns))
            SELECT ?, ?, ?, ?, ?, ?, ? WHERE ? > COALESCE(
              (SELECT MAX(sensor_minute) FROM readings WHERE peripheral_id = ? AND serial = ?), -1)
            """)
        defer { sqlite3_finalize(s) }
        try bind(identity.peripheralID.uuidString, at: 1, to: s)
        try bind(identity.serial, at: 2, to: s)
        sqlite3_bind_int(s, 3, Int32(reading.minute))
        sqlite3_bind_int(s, 4, Int32(reading.glucoseMgDl))
        sqlite3_bind_int(s, 5, Int32(reading.trendRaw))
        sqlite3_bind_int(s, 6, Int32(reading.quality))
        sqlite3_bind_double(s, 7, receivedAt.timeIntervalSince1970)
        sqlite3_bind_int(s, 8, Int32(reading.minute))
        try bind(identity.peripheralID.uuidString, at: 9, to: s)
        try bind(identity.serial, at: 10, to: s)
        guard sqlite3_step(s) == SQLITE_DONE else { throw failure() }
        return sqlite3_changes(db) == 1
    }

    public func latest(for identity: SensorIdentity) throws -> GlucoseRecord? {
        let s = try prepare("SELECT \(columns) FROM readings WHERE peripheral_id = ? AND serial = ? ORDER BY sensor_minute DESC LIMIT 1")
        defer { sqlite3_finalize(s) }
        try bindIdentity(identity, to: s)
        switch sqlite3_step(s) {
        case SQLITE_ROW: return try record(s)
        case SQLITE_DONE: return nil
        default: throw failure()
        }
    }

    /// Inclusive interval. Capped explicitly to keep a malformed clock/history from
    /// silently truncating a chart or exhausting memory.
    public func records(for identity: SensorIdentity, from start: Date, through end: Date) throws -> [GlucoseRecord] {
        guard start <= end, start.timeIntervalSince1970.isFinite, end.timeIntervalSince1970.isFinite else { throw HistoryError.invalidRecord }
        let s = try prepare("SELECT \(columns) FROM readings WHERE peripheral_id = ? AND serial = ? AND received_at >= ? AND received_at <= ? ORDER BY received_at, sensor_minute LIMIT 10001")
        defer { sqlite3_finalize(s) }
        try bindIdentity(identity, to: s)
        sqlite3_bind_double(s, 3, start.timeIntervalSince1970)
        sqlite3_bind_double(s, 4, end.timeIntervalSince1970)
        var result: [GlucoseRecord] = []
        while true {
            switch sqlite3_step(s) {
            case SQLITE_ROW: result.append(try record(s))
            case SQLITE_DONE:
                guard result.count <= 10000 else { throw HistoryError.tooManyRecords }
                return result
            default: throw failure()
            }
        }
    }

    public func identities() throws -> [SensorIdentity] {
        let s = try prepare("SELECT peripheral_id, serial FROM readings GROUP BY peripheral_id, serial ORDER BY MAX(received_at) DESC")
        defer { sqlite3_finalize(s) }
        var result: [SensorIdentity] = []
        while true {
            switch sqlite3_step(s) {
            case SQLITE_ROW: result.append(try identity(s))
            case SQLITE_DONE: return result
            default: throw failure()
            }
        }
    }

    private func identity(_ s: OpaquePointer) throws -> SensorIdentity {
        guard let rawID = sqlite3_column_text(s, 0), let rawSerial = sqlite3_column_text(s, 1),
              let id = UUID(uuidString: String(cString: rawID)) else { throw HistoryError.invalidRecord }
        return try SensorIdentity(peripheralID: id, serial: String(cString: rawSerial))
    }
    private func record(_ s: OpaquePointer) throws -> GlucoseRecord {
        let identity = try identity(s)
        let reading = SensorReading(minute: Int(sqlite3_column_int(s, 2)), glucoseMgDl: Int(sqlite3_column_int(s, 3)),
            trendRaw: Int(sqlite3_column_int(s, 4)), quality: Int(sqlite3_column_int(s, 5)), previousMgDl: nil, previous2MgDl: nil)
        let time = sqlite3_column_double(s, 6)
        guard (0...65535).contains(reading.minute), (18...800).contains(reading.glucoseMgDl),
              (-128...127).contains(reading.trendRaw), (1...255).contains(reading.quality), time.isFinite else { throw HistoryError.invalidRecord }
        return GlucoseRecord(identity: identity, reading: reading, receivedAt: Date(timeIntervalSince1970: time))
    }
    private func bindIdentity(_ identity: SensorIdentity, to s: OpaquePointer) throws {
        try bind(identity.peripheralID.uuidString, at: 1, to: s)
        try bind(identity.serial, at: 2, to: s)
    }
    private func bind(_ text: String, at index: Int32, to s: OpaquePointer) throws {
        guard sqlite3_bind_text(s, index, text, -1, transient) == SQLITE_OK else { throw failure() }
    }
    private func prepare(_ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw failure() }
        return statement
    }
    private func execute(_ sql: String) throws {
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw failure() }
    }
    private func failure() -> HistoryError { .database(String(cString: sqlite3_errmsg(db))) }
}
