import SwiftUI
import UIKit
import GlucoTrailProtocol

struct AlertSettingsView: View {
    @ObservedObject var scanner: SensorScanner
    @ObservedObject var alerts: AlertController
    let demo: Bool
    @State private var draft = AlertSettings()
    @State private var saved = false
    @State private var confirmRecovery = false

    var body: some View {
        List {
            Section {
                Text("Experimental alerts. Use your established monitoring system for care decisions. GlucoTrail cannot provide reliable continuous background glucose alarms.")
                NavigationLink("Preview alert scenarios") { AlertPreviewView() }
                    .accessibilityIdentifier("alertPreviewLink")
                Text(alerts.permission).accessibilityIdentifier("notificationPermission")
                Text(alerts.monitoring ? "Monitoring session started; only new eligible readings can alert" : "Monitoring stopped — start scanning to evaluate real readings").font(.caption)
                Button("Request notification permission") { alerts.requestPermission() }.disabled(demo)
                Link("Open iPhone notification settings", destination: URL(string: UIApplication.openSettingsURLString)!)
                Text("Focus, silent mode and system settings may delay or silence notifications. Critical Alerts are not requested. Permission does not enable alerts automatically.").font(.caption)
            }
            Section("Alert settings") {
                if demo { Text("DEMO · real alert settings are read-only") }
                Button(draft.enabled ? "Disable alerts" : "Enable alerts") { draft.enabled.toggle(); saved = false }
                    .accessibilityIdentifier("alertEnableButton").disabled(demo)
                Text(draft.enabled ? "Alerts: ON after saving" : "Alerts: OFF").accessibilityIdentifier("alertsEnabledState")
                Toggle("Low alert", isOn: $draft.lowEnabled)
                Stepper("Low: \(draft.lowMgDl) mg/dL (\(String(format: "%.1f", Double(draft.lowMgDl) / 18)) mmol/L)", value: $draft.lowMgDl, in: 18...799)
                Toggle("High alert", isOn: $draft.highEnabled)
                Stepper("High: \(draft.highMgDl) mg/dL (\(String(format: "%.1f", Double(draft.highMgDl) / 18)) mmol/L)", value: $draft.highMgDl, in: 19...800)
                Text("Thresholds are stored in mg/dL. Review the example values and choose your own settings; these are not treatment recommendations.").font(.caption)
                Toggle("Stale-reading reminder", isOn: $draft.staleEnabled)
                Stepper("Stale after \(draft.staleMinutes) minutes", value: $draft.staleMinutes, in: 3...30)
                Toggle("Disconnected reminder", isOn: $draft.disconnectedEnabled)
                Stepper("Disconnected for \(draft.disconnectedMinutes) minutes", value: $draft.disconnectedMinutes, in: 1...30)
                Stepper("Repeat glucose alert no sooner than \(draft.repeatMinutes) minutes", value: $draft.repeatMinutes, in: 5...60)
                Button("Save alert settings") {
                    scanner.configureAlerts(draft)
                    saved = alerts.archive.settings == draft && alerts.error == nil
                }.accessibilityIdentifier("saveAlertSettings")
                if saved { Text("Alert settings saved").foregroundStyle(.teal) }
            }.disabled(demo)
            Section("Background connection recovery") {
                Text(scanner.recoveryStatus)
                Button(scanner.backgroundRecovery ? "Disable background recovery" : "Enable experimental recovery…") {
                    if scanner.backgroundRecovery { scanner.setBackgroundRecovery(false) }
                    else { confirmRecovery = true }
                }.disabled(demo)
                Text("Recovery only maintains or reconnects selected Bluetooth connections when iOS allows it. Glucose advertisements are processed only in the foreground. Automatic sensor handover is always disarmed when inactive.").font(.caption)
                Text("Force-quitting can prevent Bluetooth relaunch. Reopen the app and verify reception. A previously scheduled check reminder may still appear; it is not evidence of current glucose monitoring.").font(.caption)
            }
            Section("Alert history · last 200 events") {
                Text("Requested and accepted by iOS do not mean seen or heard. Delivery is recorded only when the app observes it. History contains request times, not glucose values.").font(.caption)
                if alerts.archive.history.isEmpty { Text("No alert events recorded").accessibilityIdentifier("emptyAlertHistory") }
                ForEach(alerts.archive.history.reversed()) { event in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(alerts.title(event.kind)).bold()
                        Text(status(event.status))
                        Text("Event: \(event.date.formatted())").font(.caption)
                        Text("Requested delivery: \(event.due.formatted())").font(.caption)
                    }
                }
            }
            if let error = alerts.error { Section { Text(error).foregroundStyle(.red) } }
        }
        .navigationTitle("Alerts & safety")
        .onAppear { draft = alerts.archive.settings; alerts.refreshPermission() }
        .confirmationDialog("Enable experimental recovery?", isPresented: $confirmRecovery, titleVisibility: .visible) {
            Button("Enable connection recovery") { scanner.setBackgroundRecovery(true) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This does not enable continuous background glucose readings or reliable alarms. Physical iPhone validation is still required.")
        }
    }
    private func status(_ value: AlertHistoryEntry.Status) -> String {
        switch value {
        case .requested: return "Request recorded"
        case .acceptedBySystem: return "Accepted by iOS · delivery unconfirmed"
        case .observedDelivered: return "Delivery observed by app · not proof it was seen or heard"
        case .permissionDenied: return "Not requested · permission unavailable"
        case .failed: return "Request failed"
        case .cancelled: return "Request became obsolete · cancellation sent"
        }
    }
}

struct AlertPreviewView: View {
    @State private var result = "Choose a scenario"
    var body: some View {
        List {
            Section {
                Text("DEMO · policy simulation only. No notification is sent and no real settings or history are changed.")
                Text(result).accessibilityIdentifier("alertPreviewResult")
            }
            Section("Simulated scenarios") {
                Button("Low reading") { preview(.low) }.accessibilityIdentifier("previewLow")
                Button("High reading") { preview(.high) }.accessibilityIdentifier("previewHigh")
                Button("Missing readings") { preview(.stale) }.accessibilityIdentifier("previewStale")
                Button("Disconnected") { preview(.disconnected) }.accessibilityIdentifier("previewDisconnected")
                Button("Delayed glucose alert") { preview(.high, delayed: true) }.accessibilityIdentifier("previewDelayed")
                Button("Stopped monitoring") { preview(.high, stopped: true) }.accessibilityIdentifier("previewStopped")
            }
            Text("Force-quit behavior requires a physical iPhone test. This preview cannot prove background delivery, sound, Bluetooth restoration or iOS relaunch.").font(.caption)
        }.navigationTitle("Alert preview")
    }
    private func preview(_ kind: AlertKind, delayed: Bool = false, stopped: Bool = false) {
        let now = Date()
        let identity = try! SensorIdentity(peripheralID: UUID(), serial: "DEMO000001")
        var settings = AlertSettings(); settings.enabled = true
        settings.lowEnabled = kind == .low; settings.highEnabled = kind == .high
        settings.staleEnabled = kind == .stale; settings.disconnectedEnabled = kind == .disconnected
        var policy = AlertPolicy(settings: settings); policy.start(primary: identity, at: now)
        if kind == .low || kind == .high {
            let reading = SensorReading(minute: 60, glucoseMgDl: kind == .low ? 60 : 200, trendRaw: 0,
                quality: 100, previousMgDl: nil, previous2MgDl: nil)
            policy.accept(GlucoseRecord(identity: identity, reading: reading, receivedAt: now), now: now)
        }
        if stopped { policy.stop() }
        let plans = policy.plans(at: now.addingTimeInterval(delayed ? 121 : 0))
        result = plans.isEmpty ? "DEMO · No alert eligible" : "DEMO · \(kind.rawValue) request eligible"
    }
}
