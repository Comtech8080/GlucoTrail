import SwiftUI
import GlucoTrailProtocol

@main
struct GlucoTrailApp: App {
    @StateObject private var scanner = SensorScanner()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView(scanner: scanner)
                .tint(.teal)
                .onChange(of: scenePhase) { scanner.setForeground($0 == .active) }
        }
    }
}

struct ContentView: View {
    @ObservedObject var scanner: SensorScanner
    @AppStorage("glucoseUnitMmol") private var mmol = true
    @State private var demo = ProcessInfo.processInfo.arguments.contains("--demo")
    @State private var samples: [GlucoseRecord] = []

    var body: some View {
        TabView {
            DashboardView(scanner: scanner, mmol: mmol, demo: demo, samples: samples)
                .tabItem { Label("Dashboard", systemImage: "chart.xyaxis.line") }
            HistoryView(scanner: scanner, mmol: mmol, demo: demo, samples: samples)
                .tabItem { Label("History", systemImage: "clock") }
            ReplacementView(scanner: scanner, mmol: mmol, demo: demo, samples: samples)
                .tabItem { Label("Replacement", systemImage: "arrow.triangle.swap") }
            sensorView.tabItem { Label("Sensor", systemImage: "waveform.path.ecg") }
            settingsView.tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .onAppear { if demo { startDemo() } }
        .onChange(of: demo) { enabled in
            if enabled { startDemo() } else { samples = [] }
        }
    }

    private func startDemo() {
        scanner.stop()
        if samples.isEmpty { samples = DemoHistory.records(endingAt: Date()) }
    }

    private var sensorView: some View {
        NavigationStack {
            List {
                Section {
                    Text("Glucose reception requires the foreground. Optional alerts and experimental connection recovery are in Settings. Keep using your established monitoring system for care decisions.")
                        .font(.subheadline)
                    if demo { Label("Demo is on — Bluetooth stopped", systemImage: "play.rectangle").foregroundStyle(.orange) }
                }
                Section("Sensor status") {
                    Text(scanner.status)
                    Text(scanner.connectionStatus)
                    if let identity = scanner.savedIdentity {
                        Text("Primary serial: \(identity.serial)")
                        Text("iOS device ID: \(identity.peripheralID.uuidString)").font(.caption).textSelection(.enabled)
                    }
                    HStack {
                        Button("Start scan") { scanner.start() }.disabled(scanner.scanning || demo)
                        Spacer()
                        Button("Stop") { scanner.stop() }
                    }.buttonStyle(.bordered)
                }
                if let selected = scanner.selected {
                    Section("Packet diagnostics") {
                        Text(selected.name)
                        Text(selected.packetStatus)
                    }
                }
                Section("Nearby sensors") {
                    if scanner.savedIdentity != nil { Text("To change primary, add a sensor in the Replacement tab and confirm handover there.").font(.caption) }
                    if scanner.sensors.isEmpty { Text("Start scanning near an already activated sensor, then select its serial. GlucoTrail cannot activate a sensor.") }
                    ForEach(scanner.sensors) { sensor in
                        Button { scanner.select(sensor.id) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(sensor.name)
                                    Text(sensor.rssi == 127 ? "Signal unavailable" : "Signal: \(sensor.rssi) dBm").font(.caption)
                                }
                                Spacer()
                                if scanner.selectedID == sensor.id { Image(systemName: "checkmark.circle.fill") }
                            }
                        }.disabled(sensor.serial == nil || demo || (scanner.savedIdentity != nil && sensor.id != scanner.selectedID))
                    }
                }
            }.navigationTitle("Sensor")
        }
    }

    private var settingsView: some View {
        NavigationStack {
            List {
                Section("Display") {
                    Picker("Glucose unit", selection: $mmol) {
                        Text("mmol/L").tag(true)
                        Text("mg/dL").tag(false)
                    }.pickerStyle(.segmented).accessibilityIdentifier("unitPicker")
                    Button(demo ? "Use real sensor history" : "Show demo data") { demo.toggle() }
                        .accessibilityIdentifier("demoButton")
                    Text("Demo readings stay separate from your saved glucose history. Turning demo off leaves Bluetooth stopped until you start it.").font(.caption)
                }
                Section("Local history") {
                    Text("Readings from your primary and replacement sensors are saved on this iPhone. History is excluded from device backups and is removed when the app is deleted. Forgetting a sensor keeps its history available in the History tab.")
                    if let error = scanner.storageError { Text(error).foregroundStyle(.red) }
                    Text("Manufacturer packets received: \(scanner.packetCount)")
                    Button("Forget both sensor roles", role: .destructive) { scanner.clear() }
                }
                Section("Notifications") {
                    NavigationLink("Alerts & safety") { AlertSettingsView(scanner: scanner, alerts: scanner.alerts, demo: demo) }
                        .accessibilityIdentifier("alertsSettings")
                }
                Section("About") {
                    Text("GlucoTrail iOS · 0.5.0 (5)")
                    Text("Phase 4: configurable notifications and experimental connection recovery. Continuous background glucose and encrypted GATT remain unavailable. No TestFlight build is available yet.")
                    Link("GlucoTrail on GitHub", destination: URL(string: "https://github.com/Comtech8080/GlucoTrail")!)
                }
            }.navigationTitle("Settings")
        }
    }
}
