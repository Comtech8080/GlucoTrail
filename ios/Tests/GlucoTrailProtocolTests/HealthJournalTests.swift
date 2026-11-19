import XCTest
@testable import GlucoTrailProtocol

final class HealthJournalTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_700_000_000)
    let jpeg = Data([0xff, 0xd8, 1, 2, 0xff, 0xd9])
    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true); return url
    }
    private func insulin() -> JournalEntry {
        JournalEntry(kind: .insulin, occurredAt: now, label: "Recorded insulin", metrics: ["units": 1.5, "durationMinutes": 180])
    }
    func testEveryEntryTypeRoundTripsAndEditingKeepsIdentity() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let store = try JournalStore(root: root)
        var archive = JournalArchive()
        archive.entries = [JournalEntry(kind: .note, occurredAt: now, text: "Lunch"),
            JournalEntry(kind: .bloodPressure, occurredAt: now, metrics: ["systolic": 120, "diastolic": 80, "pulse": 60]),
            JournalEntry(kind: .weight, occurredAt: now, metrics: ["kg": 75]), insulin(),
            JournalEntry(kind: .workout, occurredAt: now, label: "Bike", metrics: ["durationMinutes": 20], machineID: "builtin-1")]
        try store.commit(archive)
        XCTAssertEqual(try JournalStore(root: root).archive, archive)
        let id = archive.entries[0].id
        archive.entries[0].text = "Edited lunch"; try store.commit(archive)
        XCTAssertEqual(store.archive.entries[0].id, id)
        XCTAssertEqual(try JournalStore(root: root).archive.entries[0].text, "Edited lunch")
    }
    func testValidationRejectsEmptyNoteNonfiniteAndCrossTypeFields() {
        XCTAssertThrowsError(try JournalEntry(kind: .note, occurredAt: now).validate())
        XCTAssertThrowsError(try JournalEntry(kind: .weight, occurredAt: now, metrics: ["kg": .nan]).validate())
        XCTAssertThrowsError(try JournalEntry(kind: .weight, occurredAt: now, metrics: ["kg": 50, "units": 5]).validate())
        XCTAssertThrowsError(try JournalEntry(kind: .note, occurredAt: now, text: "x", machineID: "builtin-1").validate())
        XCTAssertThrowsError(try JournalEntry(kind: .note, occurredAt: Date(timeIntervalSince1970: .infinity), text: "x").validate())
    }
    func testPressureChecksOrderingAndWholeNumbers() {
        for values in [["systolic": 80.0, "diastolic": 120], ["systolic": 120, "diastolic": 80.5], ["systolic": 120, "diastolic": 80, "pulse": 0]] {
            XCTAssertThrowsError(try JournalEntry(kind: .bloodPressure, occurredAt: now, metrics: values).validate())
        }
        XCTAssertNoThrow(try JournalEntry(kind: .bloodPressure, occurredAt: now, metrics: ["systolic": 120, "diastolic": 80]).validate())
    }
    func testWeightConversionAndBoundaries() {
        XCTAssertEqual(JournalUnits.kilograms(fromPounds: 100), 45.359237, accuracy: 0.000001)
        XCTAssertEqual(JournalUnits.pounds(fromKilograms: JournalUnits.kilograms(fromPounds: 155.5)), 155.5, accuracy: 0.000001)
        for value in [0.0, 501, -.infinity] { XCTAssertThrowsError(try JournalEntry(kind: .weight, occurredAt: now, metrics: ["kg": value]).validate()) }
    }
    func testNumericInputUsesLocaleDecimalAndRejectsAmbiguousGrouping() throws {
        XCTAssertEqual(try JournalUnits.number("1.5", decimalSeparator: "."), 1.5)
        XCTAssertEqual(try JournalUnits.number("1,5", decimalSeparator: ","), 1.5)
        XCTAssertThrowsError(try JournalUnits.number("1,000", decimalSeparator: "."))
        XCTAssertThrowsError(try JournalUnits.number("1,000.5", decimalSeparator: ","))
        XCTAssertThrowsError(try JournalUnits.number("nan", decimalSeparator: "."))
        XCTAssertThrowsError(try JournalUnits.number("1 000", decimalSeparator: ","))
    }
    func testInsulinRequiresNameAmountAndExplicitDuration() {
        XCTAssertNoThrow(try insulin().validate())
        var entry = insulin(); entry.label = ""; XCTAssertThrowsError(try entry.validate())
        entry = insulin(); entry.metrics["units"] = 0; XCTAssertThrowsError(try entry.validate())
        entry = insulin(); entry.metrics["durationMinutes"] = 2.5; XCTAssertThrowsError(try entry.validate())
        entry = insulin(); entry.metrics["durationMinutes"] = nil; XCTAssertThrowsError(try entry.validate())
    }
    func testCountdownUsesRecordedTimeAcrossRelaunchAndExpiry() throws {
        let entry = insulin()
        XCTAssertEqual(InsulinCountdown.state(for: entry, at: now), .remaining(seconds: 10800))
        XCTAssertEqual(InsulinCountdown.state(for: entry, at: now.addingTimeInterval(10799.5)), .remaining(seconds: 1))
        XCTAssertEqual(InsulinCountdown.state(for: entry, at: now.addingTimeInterval(10800)), .elapsed)
        XCTAssertEqual(InsulinCountdown.state(for: entry, at: now.addingTimeInterval(-1)), .clockBeforeEntry)
        let restored = try JSONDecoder().decode(JournalEntry.self, from: JSONEncoder().encode(entry))
        XCTAssertEqual(InsulinCountdown.state(for: restored, at: now.addingTimeInterval(3600)), .remaining(seconds: 7200))
        XCTAssertEqual(restored.metrics["units"], 1.5)
    }
    func testCountdownIsUnavailableForInvalidOrNonInsulinEntries() {
        XCTAssertEqual(InsulinCountdown.state(for: JournalEntry(kind: .note, occurredAt: now, text: "x"), at: now), .unavailable)
        var entry = insulin(); entry.metrics["durationMinutes"] = -1
        XCTAssertEqual(InsulinCountdown.state(for: entry, at: now), .unavailable)
    }
    func testWorkoutValidationAndEquipmentSnapshotSurvivesCatalogueRemoval() throws {
        let machine = FitnessMachine(name: "My rower")
        var archive = JournalArchive(); archive.customMachines = [machine]
        archive.entries = [JournalEntry(kind: .workout, occurredAt: now, label: machine.name,
            metrics: ["durationMinutes": 20, "sets": 3, "reps": 12, "loadKg": 5], machineID: machine.id)]
        try archive.validate(); archive.customMachines = []; try archive.validate()
        XCTAssertEqual(archive.entries[0].label, "My rower")
        archive.entries[0].metrics["sets"] = 2.5; XCTAssertThrowsError(try archive.validate())
    }
    func testArchiveRejectsDuplicateEntriesAndInvalidEquipment() {
        var archive = JournalArchive(); let entry = insulin(); archive.entries = [entry, entry]
        XCTAssertThrowsError(try archive.validate())
        archive.entries = []; archive.customMachines = [FitnessMachine(id: "../bad", name: "Bad")]
        XCTAssertThrowsError(try archive.validate())
    }
    func testPhotoCommitReopenAndDelete() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let store = try JournalStore(root: root), id = UUID()
        var archive = JournalArchive(); archive.entries = [JournalEntry(kind: .note, occurredAt: now, photoIDs: [id])]
        try store.commit(archive, newPhotos: [id: jpeg])
        XCTAssertEqual(try JournalStore(root: root).photo(id), jpeg)
        archive.entries = []; try store.commit(archive)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(id.uuidString + ".jpg").path))
        XCTAssertThrowsError(try store.photo(id))
    }
    func testSharedPhotoNotRemovedWhileStillReferenced() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let store = try JournalStore(root: root), id = UUID()
        var archive = JournalArchive(); archive.entries = (0..<2).map { _ in JournalEntry(kind: .note, occurredAt: now, photoIDs: [id]) }
        try store.commit(archive, newPhotos: [id: jpeg]); archive.entries.removeFirst(); try store.commit(archive)
        XCTAssertEqual(try store.photo(id), jpeg)
    }
    func testMissingOversizedOrUnreferencedPhotosCannotCommit() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let store = try JournalStore(root: root), id = UUID()
        var archive = JournalArchive(); archive.entries = [JournalEntry(kind: .note, occurredAt: now, photoIDs: [id])]
        XCTAssertThrowsError(try store.commit(archive))
        XCTAssertThrowsError(try store.commit(JournalArchive(), newPhotos: [id: jpeg]))
        XCTAssertThrowsError(try JournalStore.validatePhoto(Data(repeating: 0, count: 2_000_001)))
        XCTAssertThrowsError(try JournalStore.validatePhoto(Data("not JPEG".utf8)))
        XCTAssertTrue(store.archive.entries.isEmpty)
    }
    func testPhotoIndexFailureRollsBackNewFilesAndKeepsMemoryState() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let store = try JournalStore(root: root), id = UUID()
        try FileManager.default.createDirectory(at: root.appendingPathComponent("journal.json"), withIntermediateDirectories: false)
        var archive = JournalArchive(); archive.entries = [JournalEntry(kind: .note, occurredAt: now, photoIDs: [id])]
        XCTAssertThrowsError(try store.commit(archive, newPhotos: [id: jpeg]))
        XCTAssertTrue(store.archive.entries.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(id.uuidString + ".jpg").path))
    }
    func testCorruptUnknownVersionAndMissingAttachmentFailWithoutErasure() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let index = root.appendingPathComponent("journal.json"), corrupt = Data("broken".utf8)
        try corrupt.write(to: index); XCTAssertThrowsError(try JournalStore(root: root)); XCTAssertEqual(try Data(contentsOf: index), corrupt)
        var archive = JournalArchive(); archive.version = 999
        try JSONEncoder().encode(archive).write(to: index); XCTAssertThrowsError(try JournalStore(root: root))
        archive.version = 1; archive.entries = [JournalEntry(kind: .note, occurredAt: now, photoIDs: [UUID()])]
        let missing = try JSONEncoder().encode(archive); try missing.write(to: index)
        XCTAssertThrowsError(try JournalStore(root: root)); XCTAssertEqual(try Data(contentsOf: index), missing)
    }
    func testInvalidSavePreservesExistingJournalAndPhotos() throws {
        let root = try folder(); defer { try? FileManager.default.removeItem(at: root) }
        let store = try JournalStore(root: root); var archive = JournalArchive(); archive.entries = [insulin()]
        try store.commit(archive)
        let bytes = try Data(contentsOf: root.appendingPathComponent("journal.json"))
        archive.entries[0].metrics["units"] = -1; XCTAssertThrowsError(try store.commit(archive))
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("journal.json")), bytes)
    }
    func testDateAndTypeFiltering() {
        var archive = JournalArchive(); archive.entries = [insulin(), JournalEntry(kind: .note, occurredAt: now.addingTimeInterval(60), text: "Later")]
        XCTAssertEqual(archive.entries(from: now, through: now, kind: nil).count, 1)
        XCTAssertEqual(archive.entries(from: now, through: now.addingTimeInterval(100), kind: .note).first?.text, "Later")
    }
    func testExportIntervalUnitsAndPhotosAreExplicitAndSourceIsLabelled() throws {
        let id = UUID(); var archive = JournalArchive()
        archive.entries = [insulin(), JournalEntry(kind: .note, occurredAt: now, text: "Photo", photoIDs: [id]), JournalEntry(kind: .note, occurredAt: now.addingTimeInterval(60), text: "Outside")]
        let data = try JournalExport.data(archive: archive, glucose: [], start: now, end: now, demo: true, includePhotos: false, photo: { _ in XCTFail("Photo must not be read when excluded"); return self.jpeg }, now: now)
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let export = try decoder.decode(JournalExport.self, from: data)
        XCTAssertEqual(export.source, "GlucoTrail demo"); XCTAssertFalse(export.photosIncluded); XCTAssertTrue(export.photos.isEmpty)
        XCTAssertEqual(export.journal.entries.count, 2); XCTAssertEqual(export.journal.entries.first { $0.kind == .insulin }?.metrics["units"], 1.5)
        let included = try JournalExport.data(archive: archive, glucose: [], start: now, end: now, demo: false, includePhotos: true, photo: { _ in self.jpeg }, now: now)
        XCTAssertEqual(try decoder.decode(JournalExport.self, from: included).photos[id.uuidString], jpeg)
    }
    func testExportGlucoseRetainsRawValuesAndReceiptTimes() throws {
        let record = DemoHistory.alertRecord(low: false, at: now)
        let data = try JournalExport.data(archive: JournalArchive(), glucose: [record], start: now, end: now, demo: true, includePhotos: false, photo: { _ in self.jpeg }, now: now)
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let export = try decoder.decode(JournalExport.self, from: data)
        XCTAssertEqual(export.glucose.first?.glucoseMgDl, 200); XCTAssertEqual(export.glucose.first?.receivedAt, now)
        XCTAssertEqual(export.glucose.first?.serial, "DEMO000001")
    }
    func testExportRejectsReversedIntervalAndMissingPhotos() throws {
        XCTAssertThrowsError(try JournalExport.data(archive: JournalArchive(), glucose: [], start: now.addingTimeInterval(1), end: now, demo: false, includePhotos: false, photo: { _ in self.jpeg }, now: now))
        var archive = JournalArchive(); archive.entries = [JournalEntry(kind: .note, occurredAt: now, photoIDs: [UUID()])]
        XCTAssertThrowsError(try JournalExport.data(archive: archive, glucose: [], start: now, end: now, demo: false, includePhotos: true, photo: { _ in throw JournalError.missingPhoto }, now: now))
    }
}
