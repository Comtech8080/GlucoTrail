import SwiftUI
import GlucoTrailProtocol

struct HistoryView: View {
    @ObservedObject var scanner: SensorScanner
    let mmol: Bool
    let demo: Bool
    let samples: [GlucoseRecord]
    @State private var selectedIdentity: SensorIdentity?
    @State private var day = Date()
    @State private var records: [GlucoseRecord] = []
    @State private var loadError: String?

    var body: some View {
        NavigationStack {
            List {
                if demo {
                    Section { Text("DEMO · sample history only").foregroundStyle(.orange) }
                }
                Section("Browse history") {
                    if !demo {
                        Picker("Sensor", selection: $selectedIdentity) {
                            Text("Choose sensor").tag(Optional<SensorIdentity>.none)
                            ForEach(scanner.historyIdentities, id: \.self) { identity in
                                Text("\(identity.serial) · \(identity.peripheralID.uuidString.prefix(8))").tag(Optional(identity))
                            }
                        }
                    }
                    DatePicker("Date", selection: $day, displayedComponents: .date)
                    Text("Times are when this iPhone received each reading. Sensor measurement timestamps are not inferred.").font(.caption)
                }
                if let loadError { Section { Text(loadError).foregroundStyle(.red) } }
                Section("\(records.count) saved readings") {
                    if records.isEmpty {
                        Text("No readings for this sensor and date.")
                    }
                    ForEach(records.reversed()) { record in
                        HStack(alignment: .top) {
                            VStack(alignment: .leading) {
                                Text(record.receivedAt.formatted(date: .omitted, time: .standard))
                                Text("Minute \(record.reading.minute) · quality \(record.reading.quality)").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            VStack(alignment: .trailing) {
                                Text("\(glucoseText(record.reading, mmol: mmol)) \(mmol ? "mmol/L" : "mg/dL")").bold()
                                Text("\(record.reading.trendArrow) \(record.reading.trendDescription)").font(.caption)
                            }
                        }.accessibilityElement(children: .combine)
                    }
                }
            }
            .navigationTitle("History")
            .onAppear { chooseDefault(); reload() }
            .onChange(of: selectedIdentity) { _ in reload() }
            .onChange(of: day) { _ in reload() }
            .onChange(of: demo) { _ in reload() }
            .onChange(of: samples) { _ in reload() }
            .onChange(of: scanner.historyRevision) { _ in chooseDefault(); reload() }
        }
    }

    private func chooseDefault() {
        if selectedIdentity == nil {
            selectedIdentity = scanner.historyIdentities.first(where: { $0 == scanner.savedIdentity }) ?? scanner.historyIdentities.first
        }
    }
    private func reload() {
        records = []; loadError = nil
        let start = Calendar.current.startOfDay(for: day)
        guard let end = Calendar.current.date(byAdding: .day, value: 1, to: start) else { return }
        if demo {
            records = samples.filter { $0.receivedAt >= start && $0.receivedAt < end }
        } else if let identity = selectedIdentity {
            do {
                records = try scanner.historyRecords(for: identity, from: start, through: end).filter { $0.receivedAt < end }
            } catch { loadError = "Saved history could not be read. \(error)" }
        }
    }
}
