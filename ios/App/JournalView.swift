import SwiftUI
import PhotosUI
import GlucoTrailProtocol

struct JournalView: View {
    @ObservedObject var journal: JournalController
    @ObservedObject var scanner: SensorScanner
    let samples: [GlucoseRecord]
    @AppStorage("journalPounds") private var pounds = false
    @State private var kind: JournalKind?
    @State private var allDates = true
    @State private var day = Date()
    @State private var editing: JournalEntry?
    private var entries: [JournalEntry] {
        let start = allDates ? Date.distantPast : Calendar.current.startOfDay(for: day)
        let end = allDates ? Date.distantFuture : (Calendar.current.date(byAdding: .day, value: 1, to: start) ?? start)
        return journal.archive.entries(from: start, through: end, kind: kind).filter { allDates || $0.occurredAt < end }
    }
    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { clock in
            List {
                if journal.demo { Text("DEMO · journal stays in memory").foregroundStyle(.orange).accessibilityIdentifier("journalDemoBanner") }
                Section {
                    NavigationLink("Equipment catalogue") { MachineCatalogueView(journal: journal) }.accessibilityIdentifier("machineCatalogue")
                    NavigationLink("Export & backup") { JournalExportView(journal: journal, scanner: scanner, samples: samples) }.accessibilityIdentifier("journalExport")
                    Picker("Weight display", selection: $pounds) { Text("kg").tag(false); Text("lb").tag(true) }.pickerStyle(.segmented)
                }
                Section("Browse journal") {
                    Picker("Entry type", selection: $kind) {
                        Text("All entries").tag(Optional<JournalKind>.none)
                        ForEach(JournalKind.allCases) { Text($0.title).tag(Optional($0)) }
                    }
                    Toggle("All dates", isOn: $allDates)
                    if !allDates { DatePicker("Date", selection: $day, displayedComponents: .date) }
                }
                if let error = journal.error { Text(error).foregroundStyle(.red).accessibilityIdentifier("journalError") }
                Section("\(entries.count) entries") {
                    if entries.isEmpty { Text("No journal entries").accessibilityIdentifier("emptyJournal") }
                    ForEach(entries) { entry in
                        NavigationLink {
                            JournalDetailView(journal: journal, id: entry.id, pounds: pounds)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(entry.kind.title).font(.headline)
                                Text(journalSummary(entry, pounds: pounds)).lineLimit(2)
                                Text(entry.occurredAt.formatted(date: .abbreviated, time: .shortened)).font(.caption)
                                if entry.kind == .insulin { Text(countdownText(entry, at: clock.date)).font(.caption) }
                            }
                        }.accessibilityIdentifier("journalEntry-\(entry.kind.rawValue)")
                    }
                }
            }
        }
        .navigationTitle("Health journal")
        .toolbar {
            Menu {
                ForEach(JournalKind.allCases) { kind in
                    Button(kind.title) { editing = JournalEntry(kind: kind, occurredAt: Date()) }
                }
            } label: { Label("Add entry", systemImage: "plus") }.accessibilityIdentifier("addJournalEntry")
        }
        .sheet(item: $editing) { entry in JournalEntryForm(journal: journal, original: entry, pounds: pounds) }
    }
}

func journalSummary(_ entry: JournalEntry, pounds: Bool) -> String {
    func number(_ key: String) -> String { entry.metrics[key].map { String(format: "%g", $0) } ?? "—" }
    switch entry.kind {
    case .note: return entry.text.isEmpty ? "\(entry.photoIDs.count) photo(s)" : entry.text
    case .bloodPressure: return "\(number("systolic"))/\(number("diastolic")) mmHg" + (entry.metrics["pulse"] == nil ? "" : " · \(number("pulse")) bpm")
    case .weight:
        let kg = entry.metrics["kg"] ?? 0
        return String(format: "%.2f %@", pounds ? JournalUnits.pounds(fromKilograms: kg) : kg, pounds ? "lb" : "kg")
    case .insulin: return "\(entry.label) · \(number("units")) units · chosen duration \(number("durationMinutes")) min"
    case .workout:
        var result = "\(entry.label) · \(number("durationMinutes")) min"
        if entry.metrics["sets"] != nil { result += " · \(number("sets")) sets" }
        if entry.metrics["reps"] != nil { result += " · \(number("reps")) reps" }
        if entry.metrics["loadKg"] != nil { result += " · \(number("loadKg")) kg load" }
        return result
    }
}
func countdownText(_ entry: JournalEntry, at date: Date) -> String {
    switch InsulinCountdown.state(for: entry, at: date) {
    case .remaining(let seconds):
        let minutes = (seconds + 59) / 60
        return "Chosen countdown: \(minutes / 60)h \(minutes % 60)m remaining"
    case .elapsed: return "Chosen duration elapsed · not a dosing signal"
    case .clockBeforeEntry: return "Clock precedes this entry · check the recorded time"
    case .unavailable: return "Countdown unavailable"
    }
}

struct JournalDetailView: View {
    @ObservedObject var journal: JournalController
    let id: UUID
    let pounds: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var editing: JournalEntry?
    @State private var confirmDelete = false
    var body: some View {
        Group {
            if let entry = journal.archive.entries.first(where: { $0.id == id }) {
                List {
                    Text(journalSummary(entry, pounds: pounds)).accessibilityIdentifier("journalDetailSummary")
                    Text(entry.occurredAt.formatted(date: .complete, time: .shortened))
                    if entry.kind != .note && !entry.text.isEmpty { Text(entry.text) }
                    if entry.kind == .insulin {
                        TimelineView(.periodic(from: .now, by: 1)) { Text(countdownText(entry, at: $0.date)).accessibilityIdentifier("insulinCountdown") }
                        Text("This is a timer for your chosen duration, not an estimate of insulin remaining or a recommendation to dose. It does not sound an alarm when the app is closed.").font(.caption)
                    }
                    ForEach(entry.photoIDs, id: \.self) { id in
                        if let bytes = try? journal.photo(id), let image = UIImage(data: bytes) {
                            Image(uiImage: image).resizable().scaledToFit().accessibilityLabel("Note photo")
                        } else { Text("Photo unavailable").foregroundStyle(.red) }
                    }
                    if let error = journal.error { Text(error).foregroundStyle(.red) }
                    Button("Edit entry") { editing = entry }.accessibilityIdentifier("editJournalEntry")
                    Button("Delete entry", role: .destructive) { confirmDelete = true }.accessibilityIdentifier("deleteJournalEntry")
                }.navigationTitle(entry.kind.title)
            } else { Text("Entry no longer available") }
        }
        .sheet(item: $editing) { JournalEntryForm(journal: journal, original: $0, pounds: pounds) }
        .confirmationDialog("Delete this journal entry?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete permanently", role: .destructive) { if journal.delete(id) { dismiss() } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("This also removes photos that are no longer used by a note. Sensor readings are kept.") }
    }
}

struct JournalEntryForm: View {
    @ObservedObject var journal: JournalController
    let original: JournalEntry
    let pounds: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var date: Date
    @State private var text: String
    @State private var label: String
    @State private var fields: [String: String]
    @State private var machineID: String
    @State private var photoIDs: [UUID]
    @State private var newPhotos: [UUID: Data] = [:]
    @State private var selection: [PhotosPickerItem] = []
    @State private var loading = false
    @State private var localError: String?
    init(journal: JournalController, original: JournalEntry, pounds: Bool) {
        self.journal = journal; self.original = original; self.pounds = pounds
        _date = State(initialValue: original.occurredAt); _text = State(initialValue: original.text)
        _label = State(initialValue: original.label); _photoIDs = State(initialValue: original.photoIDs)
        _machineID = State(initialValue: original.machineID ?? "")
        var values = original.metrics
        if pounds, let kg = values["kg"] { values["kg"] = JournalUnits.pounds(fromKilograms: kg) }
        _fields = State(initialValue: values.mapValues { String($0) })
    }
    var body: some View {
        NavigationStack {
            Form {
                if journal.demo { Text("DEMO · this entry will not be saved to your real journal") }
                DatePicker("Occurred", selection: $date, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                inputFields
                Section("Notes") {
                    TextField("Write a note", text: $text, axis: .vertical).lineLimit(3...8).accessibilityIdentifier("journalNoteText")
                }
                if original.kind == .note { photoFields }
                if let localError { Text(localError).foregroundStyle(.red).accessibilityIdentifier("journalFormError") }
                if let error = journal.error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle(original.kind.title)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { save() }.disabled(loading).accessibilityIdentifier("saveJournalEntry") }
            }
            .task(id: selection) { await loadPhotos() }
        }
    }
    @ViewBuilder private var inputFields: some View {
        switch original.kind {
        case .note: EmptyView()
        case .bloodPressure:
            Section("Blood pressure") { field("systolic", "Systolic (mmHg)"); field("diastolic", "Diastolic (mmHg)"); field("pulse", "Pulse (bpm, optional)") }
        case .weight:
            Section("Weight") { field("kg", pounds ? "Weight (lb)" : "Weight (kg)") }
        case .insulin:
            Section("Record an administered dose") {
                TextField("Insulin name", text: $label).accessibilityIdentifier("journalInsulinName")
                field("units", "Recorded units"); field("durationMinutes", "Your countdown duration (minutes)")
                Text("Record only what was administered. This timer does not calculate insulin activity, recommend a dose or indicate when another dose is safe.").font(.caption)
            }
        case .workout:
            Section("Workout") {
                Picker("Equipment", selection: $machineID) {
                    Text("Choose equipment").tag("")
                    if let oldID = original.machineID, !journal.machines.contains(where: { $0.id == oldID }) { Text(original.label + " (removed)").tag(oldID) }
                    ForEach(journal.machines) { machine in
                        Text(machine.id == original.machineID ? original.label + " (recorded)" : machine.name).tag(machine.id)
                    }
                }.accessibilityIdentifier("workoutEquipment")
                field("durationMinutes", "Duration (minutes)"); field("sets", "Sets (optional)")
                field("reps", "Repetitions per set (optional)"); field("loadKg", "Load (kg, optional)")
            }
        }
    }
    private func field(_ key: String, _ title: String) -> some View {
        TextField(title, text: Binding(get: { fields[key] ?? "" }, set: { fields[key] = $0 }))
            .keyboardType(.decimalPad).accessibilityIdentifier("journalField-\(key)")
    }
    private var photoFields: some View {
        Section("Photos · up to four") {
            ForEach(photoIDs, id: \.self) { id in
                HStack {
                    if let bytes = newPhotos[id] ?? (try? journal.photo(id)), let image = UIImage(data: bytes) {
                        Image(uiImage: image).resizable().scaledToFit().frame(width: 80, height: 80)
                    }
                    Button("Remove photo", role: .destructive) { photoIDs.removeAll { $0 == id }; newPhotos[id] = nil }
                }
            }
            if photoIDs.count < 4 {
                PhotosPicker(selection: $selection, maxSelectionCount: 4 - photoIDs.count, matching: .images) {
                    Label("Choose photos", systemImage: "photo")
                }.disabled(loading)
            }
            if loading { ProgressView("Preparing photos…") }
            Text("Photos are resized to 1600 pixels, stored as JPEG, and stripped of source metadata. Originals in Photos are unchanged.").font(.caption)
        }
    }
    @MainActor private func loadPhotos() async {
        guard !selection.isEmpty else { return }
        loading = true; localError = nil
        defer { loading = false }
        do {
            var staged: [(UUID, Data)] = []
            for item in selection {
                guard let bytes = try await item.loadTransferable(type: Data.self) else { throw JournalError.invalid("Photo could not be loaded. If it is in iCloud, try again when available.") }
                let optimized = try await Task.detached { try JournalController.optimizedPhoto(bytes) }.value
                try Task.checkCancellation(); staged.append((UUID(), optimized))
            }
            guard photoIDs.count + staged.count <= 4 else { throw JournalError.tooLarge }
            for (id, bytes) in staged { photoIDs.append(id); newPhotos[id] = bytes }
            selection = []
        } catch is CancellationError { }
        catch { localError = error.localizedDescription }
    }
    private func save() {
        do {
            var metrics: [String: Double] = [:]
            for (key, raw) in fields where !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let value = try JournalUnits.number(raw, decimalSeparator: Locale.current.decimalSeparator ?? ".")
                metrics[key] = key == "kg" && pounds ? JournalUnits.kilograms(fromPounds: value) : value
                if key == "kg", pounds, let originalKg = original.metrics["kg"], raw == String(JournalUnits.pounds(fromKilograms: originalKg)) { metrics[key] = originalKg }
            }
            var name = label
            if original.kind == .workout {
                guard !machineID.isEmpty else { throw JournalError.invalid("Choose equipment from the catalogue.") }
                name = machineID == original.machineID ? original.label : (journal.machines.first { $0.id == machineID }?.name ?? original.label)
            }
            let entry = JournalEntry(id: original.id, kind: original.kind, occurredAt: date, label: name, text: text,
                metrics: metrics, photoIDs: photoIDs, machineID: original.kind == .workout ? machineID : nil)
            try entry.validate()
            if journal.save(entry, photos: newPhotos) { dismiss() }
        } catch { localError = error.localizedDescription }
    }
}

struct MachineCatalogueView: View {
    @ObservedObject var journal: JournalController
    @State private var name = ""
    @State private var editingID: String?
    var body: some View {
        List {
            Section("Your equipment") {
                TextField("Equipment name", text: $name).accessibilityIdentifier("machineName")
                Button(editingID == nil ? "Add equipment" : "Save equipment") {
                    if journal.saveMachine(FitnessMachine(id: editingID ?? UUID().uuidString, name: name)) { name = ""; editingID = nil }
                }.accessibilityIdentifier("saveMachine")
                if editingID != nil { Button("Cancel editing") { name = ""; editingID = nil } }
                ForEach(journal.archive.customMachines) { machine in
                    Button(machine.name) { editingID = machine.id; name = machine.name }
                        .swipeActions { Button("Remove", role: .destructive) { journal.deleteMachine(machine.id) } }
                }
                Text("Removing or renaming equipment does not change names recorded in past workouts.").font(.caption)
            }
            if let error = journal.error { Text(error).foregroundStyle(.red) }
            Section("Built-in catalogue") { ForEach(FitnessMachine.catalogue) { Text($0.name) } }
            Text("Manual logging only. No fitness-machine Bluetooth connection or exercise recommendation is provided.").font(.caption)
        }.navigationTitle("Equipment")
    }
}
