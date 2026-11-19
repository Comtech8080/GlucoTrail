import Foundation

public enum JournalError: Error, LocalizedError {
    case invalid(String), unsupportedVersion, missingPhoto, tooLarge
    public var errorDescription: String? {
        switch self {
        case .invalid(let message): return message
        case .unsupportedVersion: return "This journal version is not supported. Existing data was kept."
        case .missingPhoto: return "A referenced photo is missing. Existing journal data was kept."
        case .tooLarge: return "The data exceeds the size limit. Use fewer photos or a shorter export interval."
        }
    }
}
public enum JournalKind: String, Codable, CaseIterable, Identifiable {
    case note, bloodPressure, weight, insulin, workout
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .note: return "Note"
        case .bloodPressure: return "Blood pressure"
        case .weight: return "Weight"
        case .insulin: return "Insulin"
        case .workout: return "Workout"
        }
    }
}

/// Canonical units: mmHg, bpm, kg, insulin units, minutes, sets and repetitions.
/// This is a user-entered journal, not sensor data or a dosing model.
public struct JournalEntry: Codable, Equatable, Identifiable {
    public let id: UUID
    public var kind: JournalKind
    public var occurredAt: Date
    public var updatedAt: Date
    public var label: String
    public var text: String
    public var metrics: [String: Double]
    public var photoIDs: [UUID]
    public var machineID: String?
    public init(id: UUID = UUID(), kind: JournalKind, occurredAt: Date, label: String = "", text: String = "",
                metrics: [String: Double] = [:], photoIDs: [UUID] = [], machineID: String? = nil) {
        self.id = id; self.kind = kind; self.occurredAt = occurredAt; updatedAt = occurredAt
        self.label = label; self.text = text; self.metrics = metrics; self.photoIDs = photoIDs; self.machineID = machineID
    }
    public func validate() throws {
        guard occurredAt.timeIntervalSince1970.isFinite, updatedAt.timeIntervalSince1970.isFinite,
              label.count <= 120, text.count <= 5000, photoIDs.count <= 4,
              Set(photoIDs).count == photoIDs.count, metrics.values.allSatisfy(\.isFinite),
              machineID == nil || (machineID!.count <= 80 && !machineID!.isEmpty) else { throw JournalError.invalid("Entry has invalid or oversized fields.") }
        if kind != .note && !photoIDs.isEmpty { throw JournalError.invalid("Attach photos to a note.") }
        if kind != .workout && machineID != nil { throw JournalError.invalid("Only workouts can reference equipment.") }
        let keys = Set(metrics.keys)
        switch kind {
        case .note:
            guard keys.isEmpty, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !photoIDs.isEmpty else { throw JournalError.invalid("Write a note or attach a photo.") }
        case .bloodPressure:
            guard keys.isSubset(of: ["systolic", "diastolic", "pulse"]),
                  valid("systolic", 30...300, whole: true), valid("diastolic", 20...200, whole: true),
                  metrics["systolic"]! > metrics["diastolic"]!,
                  metrics["pulse"] == nil || valid("pulse", 20...300, whole: true) else { throw JournalError.invalid("Enter whole-number pressures (systolic 30–300, diastolic 20–200, systolic above diastolic), and optional pulse 20–300.") }
        case .weight:
            guard keys == ["kg"], valid("kg", 1...500) else { throw JournalError.invalid("Enter weight between 1 and 500 kg (2.2–1102.3 lb).") }
        case .insulin:
            guard keys == ["units", "durationMinutes"], !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  valid("units", 0.01...1000), valid("durationMinutes", 1...1440, whole: true) else { throw JournalError.invalid("Enter the insulin name, recorded amount (0.01–1000 units), and a chosen countdown duration (1–1440 whole minutes). These limits are data checks, not recommended doses.") }
        case .workout:
            guard keys.isSubset(of: ["durationMinutes", "sets", "reps", "loadKg"]),
                  !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  valid("durationMinutes", 1...1440, whole: true),
                  metrics["sets"] == nil || valid("sets", 1...100, whole: true),
                  metrics["reps"] == nil || valid("reps", 1...1000, whole: true),
                  metrics["loadKg"] == nil || valid("loadKg", 0...1000) else { throw JournalError.invalid("Choose equipment, enter 1–1440 whole minutes, and valid optional sets, repetitions and load.") }
        }
    }
    private func valid(_ key: String, _ range: ClosedRange<Double>, whole: Bool = false) -> Bool {
        guard let value = metrics[key], value.isFinite, range.contains(value) else { return false }
        return !whole || value.rounded() == value
    }
}

public struct FitnessMachine: Codable, Equatable, Identifiable {
    public let id: String
    public var name: String
    public init(id: String = UUID().uuidString, name: String) { self.id = id; self.name = name }
    public static let catalogue: [FitnessMachine] = ["Treadmill", "Stationary bike", "Elliptical", "Rowing machine", "Leg press", "Chest press", "Lat pulldown", "Seated row", "Cable machine", "Free weights", "Other activity"].enumerated().map {
        FitnessMachine(id: "builtin-\($0.offset)", name: $0.element)
    }
}

public enum InsulinCountdown: Equatable {
    case remaining(seconds: Int), elapsed, clockBeforeEntry, unavailable
    public static func state(for entry: JournalEntry, at now: Date) -> InsulinCountdown {
        guard entry.kind == .insulin, (try? entry.validate()) != nil, now.timeIntervalSince1970.isFinite,
              let minutes = entry.metrics["durationMinutes"] else { return .unavailable }
        guard now >= entry.occurredAt else { return .clockBeforeEntry }
        let remaining = entry.occurredAt.addingTimeInterval(minutes * 60).timeIntervalSince(now)
        return remaining > 0 ? .remaining(seconds: Int(ceil(remaining))) : .elapsed
    }
}
public enum JournalUnits {
    public static func kilograms(fromPounds pounds: Double) -> Double { pounds * 0.45359237 }
    public static func pounds(fromKilograms kilograms: Double) -> Double { kilograms / 0.45359237 }
}

public struct JournalArchive: Codable, Equatable {
    public var version = 1
    public var entries: [JournalEntry] = []
    public var customMachines: [FitnessMachine] = []
    public init() {}
    public var photoIDs: Set<UUID> { Set(entries.flatMap(\.photoIDs)) }
    public func validate() throws {
        guard version == 1 else { throw JournalError.unsupportedVersion }
        guard entries.count <= 5000, customMachines.count <= 100 else { throw JournalError.tooLarge }
        guard Set(entries.map(\.id)).count == entries.count,
              Set(customMachines.map(\.id)).count == customMachines.count,
              customMachines.allSatisfy({ UUID(uuidString: $0.id) != nil && !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.name.count <= 80 }) else { throw JournalError.invalid("Duplicate entry IDs or invalid equipment catalogue.") }
        for entry in entries { try entry.validate() }
    }
    public func entries(from start: Date, through end: Date, kind: JournalKind? = nil) -> [JournalEntry] {
        entries.filter { $0.occurredAt >= start && $0.occurredAt <= end && (kind == nil || $0.kind == kind) }
            .sorted { $0.occurredAt == $1.occurredAt ? $0.id.uuidString < $1.id.uuidString : $0.occurredAt > $1.occurredAt }
    }
}

/// Serialized by the app's main actor. Photos are immutable UUID-named files.
/// Write photos first, then atomically commit the index; never publish dangling references.
public final class JournalStore {
    public private(set) var archive: JournalArchive
    private let root: URL
    private var index: URL { root.appendingPathComponent("journal.json") }
    private func photoURL(_ id: UUID) -> URL { root.appendingPathComponent(id.uuidString + ".jpg") }
    public init(root: URL) throws {
        self.root = root
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let path = root.appendingPathComponent("journal.json")
        if FileManager.default.fileExists(atPath: path.path) {
            let data = try Self.read(path, limit: 32_000_000)
            archive = try JSONDecoder().decode(JournalArchive.self, from: data)
        } else { archive = JournalArchive() }
        try archive.validate()
        for id in archive.photoIDs where !FileManager.default.fileExists(atPath: photoURL(id).path) { throw JournalError.missingPhoto }
    }
    public func commit(_ candidate: JournalArchive, newPhotos: [UUID: Data] = [:]) throws {
        try candidate.validate()
        guard Set(newPhotos.keys).isSubset(of: candidate.photoIDs), Set(newPhotos.keys).isDisjoint(with: archive.photoIDs) else { throw JournalError.invalid("Photos must have new unique IDs and belong to a note.") }
        for bytes in newPhotos.values { try Self.validatePhoto(bytes) }
        for id in candidate.photoIDs.subtracting(newPhotos.keys) {
            guard archive.photoIDs.contains(id), FileManager.default.fileExists(atPath: photoURL(id).path) else { throw JournalError.missingPhoto }
        }
        let encoded = try JSONEncoder().encode(candidate)
        guard encoded.count <= 32_000_000 else { throw JournalError.tooLarge }
        var written: [UUID] = []
        do {
            for (id, bytes) in newPhotos {
                guard !FileManager.default.fileExists(atPath: photoURL(id).path) else { throw JournalError.invalid("Photo file already exists; select the photo again.") }
                try bytes.write(to: photoURL(id), options: .atomic); written.append(id)
            }
            try encoded.write(to: index, options: .atomic)
        } catch {
            for id in written { try? FileManager.default.removeItem(at: photoURL(id)) }
            throw error
        }
        let removed = archive.photoIDs.subtracting(candidate.photoIDs)
        archive = candidate
        // Cleanup failure never rolls back a committed journal or deletes unrelated files.
        for id in removed { try? FileManager.default.removeItem(at: photoURL(id)) }
    }
    public func photo(_ id: UUID) throws -> Data {
        guard archive.photoIDs.contains(id) else { throw JournalError.missingPhoto }
        let bytes = try Self.read(photoURL(id), limit: 2_000_000); try Self.validatePhoto(bytes); return bytes
    }
    private static func read(_ url: URL, limit: Int) throws -> Data {
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard let size = attributes[.size] as? NSNumber, size.intValue <= limit else { throw JournalError.tooLarge }
        let bytes = try Data(contentsOf: url)
        guard bytes.count <= limit else { throw JournalError.tooLarge }; return bytes
    }
    public static func validatePhoto(_ bytes: Data) throws {
        guard bytes.count <= 2_000_000 else { throw JournalError.tooLarge }
        guard bytes.count >= 4, bytes.prefix(2) == Data([0xff, 0xd8]), bytes.suffix(2) == Data([0xff, 0xd9]) else { throw JournalError.invalid("Attachment must be an optimized JPEG photo.") }
    }
}

public struct JournalGlucoseExport: Codable {
    public let peripheralID: UUID
    public let serial: String
    public let sensorMinute: Int
    public let receivedAt: Date
    public let glucoseMgDl: Int
    public let trendRaw: Int
    public let quality: Int
    public init(_ record: GlucoseRecord) {
        peripheralID = record.identity.peripheralID; serial = record.identity.serial
        sensorMinute = record.reading.minute; receivedAt = record.receivedAt
        glucoseMgDl = record.reading.glucoseMgDl; trendRaw = record.reading.trendRaw; quality = record.reading.quality
    }
}
public struct JournalExport: Codable {
    public let formatVersion: Int
    public let source: String
    public let exportedAt: Date
    public let start: Date
    public let end: Date
    public let journal: JournalArchive
    public let glucose: [JournalGlucoseExport]
    public let photosIncluded: Bool
    public let photos: [String: Data]
    public static func data(archive: JournalArchive, glucose: [GlucoseRecord], start: Date, end: Date,
                            demo: Bool, includePhotos: Bool, photo: (UUID) throws -> Data, now: Date) throws -> Data {
        try archive.validate()
        guard start.timeIntervalSince1970.isFinite, end.timeIntervalSince1970.isFinite, start <= end else { throw JournalError.invalid("Choose a valid export interval.") }
        guard glucose.count <= 20000 else { throw JournalError.tooLarge }
        var subset = archive; subset.entries = archive.entries(from: start, through: end)
        var attachments: [String: Data] = [:]; var size = 0
        if includePhotos {
            for id in subset.photoIDs {
                let bytes = try photo(id); try JournalStore.validatePhoto(bytes); size += bytes.count
                guard size <= 20_000_000 else { throw JournalError.tooLarge }
                attachments[id.uuidString] = bytes
            }
        }
        let export = JournalExport(formatVersion: 1, source: demo ? "GlucoTrail demo" : "GlucoTrail iOS",
            exportedAt: now, start: start, end: end, journal: subset,
            glucose: glucose.filter { $0.receivedAt >= start && $0.receivedAt <= end }.map(JournalGlucoseExport.init),
            photosIncluded: includePhotos, photos: attachments)
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601; encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(export)
        guard data.count <= 32_000_000 else { throw JournalError.tooLarge }
        return data
    }
}
