import SwiftUI
import UniformTypeIdentifiers
import GlucoTrailProtocol

struct JournalExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw JournalError.invalid("Export data unavailable") }
        self.data = data
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct JournalExportView: View {
    @ObservedObject var journal: JournalController
    @ObservedObject var scanner: SensorScanner
    let samples: [GlucoseRecord]
    @State private var start = Calendar.current.date(byAdding: .month, value: -1, to: Date()) ?? Date()
    @State private var end = Date()
    @State private var includeGlucose = false
    @State private var includePhotos = false
    @State private var document: JournalExportDocument?
    @State private var exporting = false
    @State private var message: String?
    @State private var failure: String?
    var body: some View {
        List {
            if journal.demo { Text("DEMO · exports contain demo data only").foregroundStyle(.orange) }
            Section("Choose data") {
                DatePicker("From", selection: $start)
                DatePicker("Through", selection: $end)
                Toggle("Include glucose readings and sensor IDs", isOn: $includeGlucose)
                Toggle("Include note photos", isOn: $includePhotos)
                Text("All journal entry types in this interval and your equipment catalogue are included. Glucose and photos are optional. Dates are exported in UTC; values include explicit units.").font(.caption)
            }
            Section {
                Text("This export contains private health information. The JSON file is not encrypted. Save it only to a location you trust; selecting a cloud provider can upload it.")
                Button("Prepare export") { prepare() }.accessibilityIdentifier("prepareJournalExport")
                if let message { Text(message).accessibilityIdentifier("journalExportStatus") }
                if document != nil { Button("Save JSON file…") { exporting = true }.accessibilityIdentifier("saveJournalExport") }
                if let failure { Text(failure).foregroundStyle(.red) }
            }
            Section("Backup planning") {
                Text("App data is excluded from device backups. Deleting GlucoTrail deletes its local journal, photos and glucose history.")
                Text("Make periodic exports and keep a protected second copy. Include photos if you need them in the copy. Verify the file opens and contains the expected dates and counts.")
                Text("This is a portable export, not a restorable app backup. Import/restore is not implemented. Notification settings, sensor roles and transition history are not included. Do not uninstall the app assuming this file can restore it.")
            }
        }
        .navigationTitle("Export & backup")
        .onChange(of: start) { _ in clear() }
        .onChange(of: end) { _ in clear() }
        .onChange(of: includePhotos) { _ in clear() }
        .onChange(of: includeGlucose) { _ in clear() }
        .onChange(of: journal.archive) { _ in clear() }
        .onChange(of: journal.demo) { _ in clear() }
        .fileExporter(isPresented: $exporting, document: document, contentType: .json,
                      defaultFilename: journal.demo ? "GlucoTrail-Demo-Export" : "GlucoTrail-Health-Export") { result in
            document = nil
            switch result {
            case .success: message = "Export saved to your chosen location"
            case .failure(let error): failure = "Export was not saved: \(error.localizedDescription)"
            }
        }
    }
    private func clear() { document = nil; message = nil; failure = nil }
    private func prepare() {
        clear()
        do {
            guard start <= end else { throw JournalError.invalid("The start must not be after the end.") }
            if !journal.demo, let error = journal.error { throw JournalError.invalid(error) }
            var readings: [GlucoseRecord] = []
            if includeGlucose {
                if journal.demo { readings = samples.filter { $0.receivedAt >= start && $0.receivedAt <= end } }
                else {
                    if let error = scanner.storageError { throw JournalError.invalid(error) }
                    for identity in scanner.historyIdentities {
                        readings += try scanner.historyRecords(for: identity, from: start, through: end)
                        guard readings.count <= 20000 else { throw JournalError.tooLarge }
                    }
                }
            }
            let data = try JournalExport.data(archive: journal.archive, glucose: readings, start: start, end: end,
                demo: journal.demo, includePhotos: includePhotos, photo: journal.photo, now: Date())
            document = JournalExportDocument(data: data)
            message = "Ready to save: \(journal.archive.entries(from: start, through: end).count) journal entries, \(readings.count) glucose readings · \(data.count) bytes"
        } catch { failure = error.localizedDescription }
    }
}
