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
    @State private var demo = false

    var body: some View {
        TabView {
            NavigationStack {
                List {
                    Section {
                        Label("Phase 1 · sensor foundation", systemImage: "wave.3.right")
                            .font(.headline)
                        Text("Foreground reception test only. No glucose alarms or background monitoring. Keep using your established monitoring app for care decisions.")
                            .font(.subheadline)
                    }
                    if demo {
                        Section("Demo — sample data, not your glucose") {
                            Text(mmol ? "5.8 mmol/L" : "104 mg/dL").font(.largeTitle.bold())
                            Text("This sample only previews the screen. Bluetooth scanning is stopped.")
                        }
                    } else {
                        Section("Sensor status") {
                            Text(scanner.status)
                            Text(scanner.connectionStatus).font(.subheadline)
                            if let identity = scanner.savedIdentity {
                                Text("Saved serial: \(identity.serial)")
                                Text("Connects only to your saved iOS device identity. A matching name alone does not replace it.").font(.caption)
                            }
                            HStack {
                                Button("Start scan") { scanner.start() }.disabled(scanner.scanning)
                                Spacer()
                                Button("Stop") { scanner.stop() }
                            }.buttonStyle(.bordered)
                        }
                        if let sensor = scanner.selected {
                            Section("Selected sensor") {
                                Text(sensor.name).font(.headline)
                                if sensor.serial == nil {
                                    Text("Serial not received. Confirm this device is your sensor before interpreting its data.")
                                }
                                TimelineView(.periodic(from: .now, by: 1)) { tick in
                                    readingView(sensor, at: tick.date)
                                }
                                Text(sensor.packetStatus).font(.caption)
                            }
                        }
                        Section("Nearby sensors") {
                            if scanner.sensors.isEmpty {
                                Text("Start scanning near an already activated sensor. Discovery may take a minute. This prototype cannot start a new sensor.")
                            }
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
                                }.disabled(sensor.serial == nil)
                            }
                        }
                    }
                }
                .navigationTitle("GlucoTrail")
            }.tabItem { Label("Sensor", systemImage: "waveform.path.ecg") }

            NavigationStack {
                List {
                    Section("Display") {
                        Toggle("Use mmol/L", isOn: $mmol)
                        Toggle("Show demo data", isOn: $demo)
                            .onChange(of: demo) { if $0 { scanner.stop() } }
                    }
                    Section("Diagnostics") {
                        Text("Manufacturer packets received: \(scanner.packetCount)")
                        Text("Sensor UUID, advertised serial, and unit preference are saved locally. Readings stay in memory. No encrypted GATT readings or background alarms are enabled.")
                        Button("Forget sensor and clear session", role: .destructive) { scanner.clear(); demo = false }
                    }
                    Section("App Updates") {
                        Text("iOS Phase 1 · 0.2.0 (2)")
                        Text("iPhone updates will be delivered through TestFlight, then the App Store. No TestFlight build is available yet.")
                        Link("GlucoTrail on GitHub", destination: URL(string: "https://github.com/Comtech8080/GlucoTrail")!)
                    }
                }.navigationTitle("Settings")
            }.tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }

    @ViewBuilder private func readingView(_ sensor: NearbySensor, at date: Date) -> some View {
        if let reading = sensor.timeline.reading, let received = sensor.timeline.receivedAt {
            let stale = sensor.timeline.isStale(at: date)
            let current = !stale && sensor.lastPacketValid && scanner.scanning
            VStack(alignment: .leading, spacing: 8) {
                Text(current ? "Latest verified reading" : "Last verified reading — not current")
                    .font(.subheadline).foregroundStyle(current ? Color.primary : Color.orange)
                Text(mmol ? String(format: "%.1f mmol/L", reading.mmolL) : "\(reading.glucoseMgDl) mg/dL")
                    .font(.largeTitle.bold())
                Text("Sensor minute \(reading.minute) · quality \(reading.quality)")
                Text("New sensor minute received \(max(0, Int(date.timeIntervalSince(received)))) seconds ago")
                    .font(.caption)
                if stale { Text("No newer reading for at least 5 minutes.").foregroundStyle(.orange) }
            }
        } else {
            Text("Waiting for a valid glucose reading…")
        }
    }
}
