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
        samples = DemoHistory.records(endingAt: Date())
    }

    private var sensorView: some View {
        NavigationStack {
            List {
                Section {
                    Text("Foreground reception only. No glucose alarms or background monitoring. Keep using your established monitoring app for care decisions.")
                        .font(.subheadline)
                    if demo { Label("Demo is on — Bluetooth stopped", systemImage: "play.rectangle").foregroundStyle(.orange) }
                }
                Section("Sensor status") {
                    Text(scanner.status)
                    Text(scanner.connectionStatus)
                    if let identity = scanner.savedIdentity {
                        Text("Saved serial: \(identity.serial)")
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
                        }.disabled(sensor.serial == nil || demo)
                    }
                }
            }.navigationTitle("Sensor")
        }
    }

    private var settingsView: some View {
        NavigationStack {
            List {
                Section("Display") {
                    Toggle("Use mmol/L", isOn: $mmol).accessibilityIdentifier("unitToggle")
                    Toggle("Show demo data", isOn: $demo).accessibilityIdentifier("demoToggle")
                    Text("Demo readings stay separate from your saved glucose history. Turning demo off leaves Bluetooth stopped until you start it.").font(.caption)
                }
                Section("Local history") {
                    Text("Readings from your selected sensor are saved on this iPhone. History is excluded from device backups and is removed when the app is deleted. Forgetting a sensor keeps its history available in the History tab.")
                    if let error = scanner.storageError { Text(error).foregroundStyle(.red) }
                    Text("Manufacturer packets received: \(scanner.packetCount)")
                    Button("Forget selected sensor", role: .destructive) { scanner.clear() }
                }
                Section("About") {
                    Text("GlucoTrail iOS · 0.3.0 (3)")
                    Text("Phase 2: dashboard and history. Encrypted GATT readings, background monitoring and alarms are not enabled. No TestFlight build is available yet.")
                    Link("GlucoTrail on GitHub", destination: URL(string: "https://github.com/Comtech8080/GlucoTrail")!)
                }
            }.navigationTitle("Settings")
        }
    }
}
