import SwiftUI
import Charts
import GlucoTrailProtocol

struct ReplacementView: View {
    @ObservedObject var scanner: SensorScanner
    let mmol: Bool
    let demo: Bool
    let samples: [GlucoseRecord]
    @State private var primaryHistory: [GlucoseRecord] = []
    @State private var replacementHistory: [GlucoseRecord] = []
    @State private var window: GraphWindow = .six
    @State private var graphMode = "Compare"
    @State private var confirmHandover = false
    @State private var confirmAutomatic = false
    @State private var reference = ""
    @State private var loadError: String?
    @State private var demoMonitor = SensorPairMonitor()
    @State private var preparedDemoEnd: Date?

    private var state: SensorPairState { demo ? demoMonitor.state : scanner.pairState }
    private var demoEnd: Date { samples.last?.receivedAt ?? Date() }
    private var ready: Bool { demo ? demoMonitor.replacementReady(at: demoEnd) : scanner.replacementReady }
    private var identities: (SensorIdentity?, SensorIdentity?) {
        if let replacement = state.replacement { return (state.primary, replacement) }
        if let transition = state.transitions.last(where: { $0.kind == .manualHandover || $0.kind == .automaticHandover }), transition.to == state.primary {
            return (transition.from, transition.to)
        }
        return (state.primary, nil)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if demo { Text("DEMO · two simulated sensors").font(.headline).foregroundStyle(.orange) }
                    Text("Primary: \(state.primary?.serial ?? "None")").font(.headline).accessibilityIdentifier("pairPrimary")
                    Text("Replacement: \(state.replacement?.serial ?? "None")").accessibilityIdentifier("pairReplacement")
                    if !demo {
                        Text("Primary connection: \(scanner.connectionStatus)").font(.caption)
                        Text("Replacement connection: \(scanner.replacementConnectionStatus)").font(.caption)
                        if let message = scanner.pairMessage { Text(message).font(.callout) }
                        if let error = scanner.storageError { Text(error).foregroundStyle(.red) }
                    }
                    if state.replacement != nil {
                        Text(ready ? "Ready for handover" : "Waiting for three consecutive fresh post-warm-up readings")
                            .foregroundStyle(ready ? Color.teal : Color.orange).accessibilityIdentifier("replacementReadiness")
                        Button(demo ? "Simulate handover" : "Switch primary sensor") { confirmHandover = true }
                            .buttonStyle(.borderedProminent).disabled(!ready).accessibilityIdentifier("handoverButton")
                        if !demo {
                            Button("Remove replacement") { scanner.removeReplacement() }
                            Button(scanner.automaticHandover ? "Disable automatic handover" : "Enable automatic handover…") {
                                if scanner.automaticHandover { scanner.setAutomaticHandover(false) }
                                else { confirmAutomatic = true }
                            }.disabled(!scanner.scanning)
                            Text(scanner.automaticHandover ? "Automatic handover: ON for this foreground session" : "Automatic handover: OFF")
                                .font(.subheadline).accessibilityIdentifier("automaticState")
                        }
                    } else if !demo, state.primary != nil {
                        Text("Choose an already activated replacement below. Primary reception continues while it is added.")
                        ForEach(scanner.sensors.filter { $0.serial != nil && $0.id != state.primary?.peripheralID && $0.serial != state.primary?.serial }) { sensor in
                            Button("Add \(sensor.serial ?? sensor.name) as replacement") { scanner.selectReplacement(sensor.id) }
                        }
                        if scanner.sensors.filter({ $0.serial != nil && $0.id != state.primary?.peripheralID && $0.serial != state.primary?.serial }).isEmpty {
                            Text("No different AiDEX X sensor found. Start scanning in the Sensor tab.").foregroundStyle(.secondary)
                        }
                    } else if state.primary == nil {
                        Text("Select your primary sensor in the Sensor tab first.")
                    }
                    Text(demo ? "This simulated handover affects the replacement preview only. Real sensor roles and the dashboard demo stay unchanged." : "Handover changes which sensor supplies the dashboard. It does not activate a sensor or change its readings. Alert eligibility restarts and requires new primary readings. Continuous background glucose is unavailable.")
                        .font(.caption).foregroundStyle(.secondary)
                    Divider()
                    Text("Sensor overlap").font(.title2.bold())
                    Picker("Graph mode", selection: $graphMode) {
                        ForEach(["Primary", "Replacement", "Compare"], id: \.self) { Text($0).tag($0) }
                    }.pickerStyle(.segmented).accessibilityIdentifier("overlapMode")
                    Picker("Time range", selection: $window) {
                        ForEach(GraphWindow.allCases) { Text("\($0.rawValue) hours").tag($0) }
                    }.pickerStyle(.menu)
                    Text("A: \(identities.0?.serial ?? "None") · B: \(identities.1?.serial ?? "None")").font(.caption)
                    if state.replacement == nil && identities.1 != nil {
                        Text("Handover completed. A shows the previous primary; B is now primary.").font(.caption)
                    }
                    if let loadError { Text(loadError).foregroundStyle(.red) }
                    TimelineView(.periodic(from: .now, by: 30)) { tick in
                        overlapChart(end: demo ? demoEnd : tick.date)
                        comparison(at: demo ? demoEnd : tick.date)
                    }
                    Text("Phone receipt times. Missing minutes remain gaps. The two raw streams are never calibrated against each other.")
                        .font(.caption).foregroundStyle(.secondary)
                    Divider()
                    Text("Transition history").font(.title2.bold())
                    if state.transitions.isEmpty { Text("No role changes recorded.") }
                    ForEach(state.transitions.reversed()) { event in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(transitionTitle(event.kind)).bold()
                            Text("\(event.from?.serial ?? "None") → \(event.to?.serial ?? "None")")
                            Text(event.date.formatted(date: .abbreviated, time: .standard)).font(.caption)
                        }.accessibilityElement(children: .combine)
                    }
                }.padding()
            }
            .navigationTitle("Replacement")
            .onAppear { if demo { prepareDemo() }; reload() }
            .onChange(of: scanner.historyRevision) { _ in reload() }
            .onChange(of: samples) { _ in if demo { prepareDemo() }; reload() }
            .onChange(of: demo) { _ in
                reference = ""
                if demo { prepareDemo() }
                else { preparedDemoEnd = nil; demoMonitor = SensorPairMonitor() }
                reload()
            }
            .onChange(of: mmol) { _ in reference = "" }
            .confirmationDialog("Switch primary sensor?", isPresented: $confirmHandover, titleVisibility: .visible) {
                Button("Confirm handover") {
                    reference = ""
                    if demo { try? demoMonitor.promote(at: demoEnd) }
                    else { scanner.promoteReplacement() }
                    reload()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The replacement will become primary. The old sensor's saved history is kept. Readiness is checked again when you confirm.")
            }
            .confirmationDialog("Enable automatic handover?", isPresented: $confirmAutomatic, titleVisibility: .visible) {
                Button("Enable for this session") { scanner.setAutomaticHandover(true) }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("After 15 minutes without a new primary reading, a ready replacement may take over. Bluetooth loss is not proof of expiry. This resets to OFF on stop, backgrounding, Bluetooth loss, clock jumps, or relaunch.")
            }
        }
    }

    private func prepareDemo() {
        guard let primary = samples.last?.identity, let replacement = DemoHistory.replacementRecords(from: samples).last?.identity else { return }
        guard preparedDemoEnd != demoEnd else { return }
        var monitor = SensorPairMonitor(state: SensorPairState(primary: primary, replacement: replacement))
        monitor.beginMonitoring()
        for record in samples.suffix(3) { monitor.accept(record) }
        for record in DemoHistory.replacementRecords(from: samples).suffix(3) { monitor.accept(record) }
        demoMonitor = monitor
        preparedDemoEnd = demoEnd
    }

    private func reload() {
        primaryHistory = []; replacementHistory = []; loadError = nil
        reference = ""
        if demo {
            primaryHistory = samples
            replacementHistory = DemoHistory.replacementRecords(from: samples)
            return
        }
        do {
            let now = Date(), start = GraphWindow.threeDays.start(endingAt: Date())
            if let identity = identities.0 { primaryHistory = try scanner.historyRecords(for: identity, from: start, through: now) }
            if let identity = identities.1 { replacementHistory = try scanner.historyRecords(for: identity, from: start, through: now) }
        } catch { loadError = "History could not be loaded: \(error)" }
    }

    @ViewBuilder private func overlapChart(end: Date) -> some View {
        let a = GlucoseGraph.points(graphMode == "Replacement" ? [] : primaryHistory, window: window, endingAt: end)
        let b = GlucoseGraph.points(graphMode == "Primary" ? [] : replacementHistory, window: window, endingAt: end)
        if a.isEmpty && b.isEmpty {
            Text("No readings in this window").frame(maxWidth: .infinity).frame(height: 220)
        } else {
            Chart {
                ForEach(a) { point in
                    LineMark(x: .value("Received", point.record.receivedAt), y: .value("Glucose", value(point.record)), series: .value("Segment", "A-\(point.segment)"))
                        .foregroundStyle(by: .value("Sensor", "A"))
                    PointMark(x: .value("Received", point.record.receivedAt), y: .value("Glucose", value(point.record)))
                        .foregroundStyle(by: .value("Sensor", "A")).symbolSize(8)
                }
                ForEach(b) { point in
                    LineMark(x: .value("Received", point.record.receivedAt), y: .value("Glucose", value(point.record)), series: .value("Segment", "B-\(point.segment)"))
                        .foregroundStyle(by: .value("Sensor", "B"))
                    PointMark(x: .value("Received", point.record.receivedAt), y: .value("Glucose", value(point.record)))
                        .foregroundStyle(by: .value("Sensor", "B")).symbolSize(8)
                }
            }
            .chartForegroundStyleScale(["A": Color.teal, "B": Color.orange])
            .chartXScale(domain: window.start(endingAt: end)...end)
            .chartYAxisLabel(mmol ? "mmol/L" : "mg/dL")
            .frame(height: 240).accessibilityIdentifier("overlapChart")
        }
    }

    @ViewBuilder private func comparison(at date: Date) -> some View {
        let first = demo ? primaryHistory.last : (scanner.recordValidatedThisSession ? scanner.latestRecord : nil)
        let second = demo ? replacementHistory.last : (scanner.replacementValidatedThisSession ? scanner.replacementRecord : nil)
        if state.replacement == nil {
            Text("Add a replacement to compare current readings.").font(.callout)
        } else if let pair = SensorComparison.pair(primary: first, replacement: second, now: date) {
            Text("Replacement − primary: \(difference(Double(pair.signedDifferenceMgDl)))").bold()
                .accessibilityIdentifier("sensorDifference")
            Text("Receipts are within 2 minutes. This is a comparison, not a calibration.").font(.caption)
            TextField("Optional meter reference (\(mmol ? "mmol/L" : "mg/dL"))", text: $reference)
                .keyboardType(.decimalPad).textFieldStyle(.roundedBorder).accessibilityIdentifier("meterReference")
            if !reference.isEmpty {
                if let input = Double(reference.replacingOccurrences(of: ",", with: ".")),
                   let delta = pair.differences(fromReferenceMgDl: mmol ? input * 18 : input) {
                    Text("Primary − reference: \(difference(delta.primary))")
                    Text("Replacement − reference: \(difference(delta.replacement))")
                    Text("Reference is not saved. No display correction or sensor command is applied.").font(.caption)
                } else { Text("Enter a reference between 18 and 800 mg/dL (1.0–44.4 mmol/L).").foregroundStyle(.orange) }
            }
        } else {
            Text("Waiting for fresh post-warm-up readings from both sensors, received within 2 minutes of each other.").font(.callout)
        }
    }
    private func value(_ record: GlucoseRecord) -> Double { mmol ? record.reading.mmolL : Double(record.reading.glucoseMgDl) }
    private func difference(_ value: Double) -> String {
        String(format: mmol ? "%+.1f mmol/L" : "%+.0f mg/dL", mmol ? value / 18 : value)
    }
    private func transitionTitle(_ kind: SensorTransition.Kind) -> String {
        switch kind {
        case .primarySelected: return "Primary selected"
        case .replacementSelected: return "Replacement selected"
        case .replacementRemoved: return "Replacement removed"
        case .manualHandover: return "Manual handover"
        case .automaticHandover: return "Automatic handover · primary readings absent"
        case .forgotten: return "Sensor roles cleared"
        }
    }
}
