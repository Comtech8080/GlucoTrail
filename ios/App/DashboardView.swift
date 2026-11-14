import SwiftUI
import Charts
import GlucoTrailProtocol

func glucoseText(_ reading: SensorReading, mmol: Bool) -> String {
    mmol ? String(format: "%.1f", reading.mmolL) : "\(reading.glucoseMgDl)"
}

struct DashboardView: View {
    @ObservedObject var scanner: SensorScanner
    let mmol: Bool
    let demo: Bool
    let samples: [GlucoseRecord]
    @State private var window: GraphWindow = .six
    @State private var records: [GlucoseRecord] = []
    @State private var loadError: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if demo {
                        Label("DEMO · sample data, not your glucose", systemImage: "play.rectangle.fill")
                            .font(.headline).foregroundStyle(.orange).accessibilityIdentifier("demoBanner")
                    }
                    TimelineView(.periodic(from: .now, by: 1)) { tick in
                        latestCard(record: demo ? samples.last : scanner.latestRecord, at: tick.date)
                    }
                    if !demo {
                        VStack(alignment: .leading, spacing: 6) {
                            Label(scanner.scanning ? "Advertisement reception on" : "Reception stopped", systemImage: scanner.scanning ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                            Text(scanner.connectionStatus).font(.caption)
                            if scanner.savedIdentity == nil { Text("Choose your sensor in the Sensor tab to begin saving readings.") }
                        }
                        if let error = scanner.storageError { Text(error).font(.callout).foregroundStyle(.red) }
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Text("Glucose history").font(.headline)
                            Spacer()
                            Picker("Graph range", selection: $window) {
                                ForEach(GraphWindow.allCases) { range in Text("\(range.rawValue) hours").tag(range) }
                            }.pickerStyle(.menu).accessibilityIdentifier("graphRange")
                        }
                        if let loadError, !demo { Text(loadError).foregroundStyle(.red) }
                        TimelineView(.periodic(from: .now, by: 60)) { tick in
                            GlucoseChart(records: demo ? samples : records, window: window,
                                end: demo ? (samples.last?.receivedAt ?? tick.date) : tick.date, mmol: mmol)
                        }
                        Text("Phone receipt time · \(window.rawValue)-hour view").font(.caption)
                            .accessibilityIdentifier("graphWindowLabel")
                        Text("Gaps indicate missing sensor minutes or reception interruptions. No estimated readings are added.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Text("Foreground prototype · no glucose alarms. Continue using your established monitoring app.")
                        .font(.footnote).foregroundStyle(.secondary)
                }.padding()
            }
            .navigationTitle("GlucoTrail")
            .onAppear(perform: reload)
            .onChange(of: scanner.historyRevision) { _ in reload() }
            .onChange(of: scanner.savedIdentity) { _ in reload() }
            .onChange(of: demo) { _ in reload() }
        }
    }

    @ViewBuilder private func latestCard(record: GlucoseRecord?, at now: Date) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if let record {
                let freshness = ReadingFreshness.evaluate(record: record, receiving: scanner.scanning,
                    validatedThisSession: scanner.recordValidatedThisSession, now: now)
                Text(demo ? "Sample reading" : freshnessTitle(freshness))
                    .font(.headline).foregroundStyle(demo || freshness == .recent ? Color.primary : Color.orange)
                    .accessibilityIdentifier("readingFreshness")
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(glucoseText(record.reading, mmol: mmol)).font(.system(size: 52, weight: .bold, design: .rounded))
                        .minimumScaleFactor(0.6).lineLimit(1).accessibilityIdentifier("latestGlucose")
                    Text(record.reading.trendArrow).font(.largeTitle).accessibilityLabel(record.reading.trendDescription)
                    Text(mmol ? "mmol/L" : "mg/dL").font(.subheadline)
                }
                Text(record.reading.trendDescription + (demo || freshness == .recent ? "" : " at last reading"))
                Text("Received \(record.receivedAt.formatted(date: .abbreviated, time: .standard))").font(.subheadline)
                Text("Sensor \(record.identity.serial) · minute \(record.reading.minute)").font(.caption).foregroundStyle(.secondary)
                if !demo && freshness != .recent {
                    Text("Historical value — not a current glucose reading.").foregroundStyle(.orange)
                }
            } else {
                Text("No glucose reading yet").font(.title2.bold())
                Text("Select your sensor and start reception. Only validated, newly received sensor minutes appear here.")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding().background(Color.teal.opacity(0.08), in: RoundedRectangle(cornerRadius: 20))
    }

    private func freshnessTitle(_ freshness: ReadingFreshness) -> String {
        switch freshness {
        case .recent: return "Recently received glucose"
        case .stale: return "Stale reading · check reception"
        case .historical: return "Saved reading · awaiting new data"
        case .unavailable: return "No glucose reading"
        }
    }

    private func reload() {
        records = []; loadError = nil
        guard !demo, let identity = scanner.savedIdentity else { return }
        do {
            let now = Date()
            records = try scanner.historyRecords(for: identity, from: GraphWindow.threeDays.start(endingAt: now), through: now)
        } catch { loadError = "History could not be loaded. \(error)" }
    }
}

struct GlucoseChart: View {
    let records: [GlucoseRecord]
    let window: GraphWindow
    let end: Date
    let mmol: Bool

    var body: some View {
        let points = GlucoseGraph.points(records, window: window, endingAt: end)
        if points.isEmpty {
            VStack(spacing: 12) {
                Image(systemName: "chart.xyaxis.line").font(.largeTitle)
                Text("No saved readings in this time range")
            }.foregroundStyle(.secondary).frame(maxWidth: .infinity).frame(height: 220)
        } else {
            Chart(points) { point in
                LineMark(x: .value("Received", point.record.receivedAt),
                    y: .value(mmol ? "mmol/L" : "mg/dL", value(point.record)),
                    series: .value("Reception segment", point.segment))
                    .interpolationMethod(.linear).foregroundStyle(.teal)
                PointMark(x: .value("Received", point.record.receivedAt),
                    y: .value(mmol ? "mmol/L" : "mg/dL", value(point.record)))
                    .symbolSize(8).foregroundStyle(.teal)
                    .accessibilityLabel(point.record.receivedAt.formatted(date: .abbreviated, time: .shortened))
                    .accessibilityValue("\(glucoseText(point.record.reading, mmol: mmol)) \(mmol ? "mmol/L" : "mg/dL")")
            }
            .chartXScale(domain: window.start(endingAt: end)...end)
            .chartYAxisLabel(mmol ? "mmol/L" : "mg/dL")
            .chartLegend(.hidden)
            .frame(height: 240)
            .accessibilityIdentifier("glucoseChart")
        }
    }
    private func value(_ record: GlucoseRecord) -> Double {
        mmol ? record.reading.mmolL : Double(record.reading.glucoseMgDl)
    }
}
