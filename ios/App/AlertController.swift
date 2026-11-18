import Foundation
import Combine
import UserNotifications
import GlucoTrailProtocol

/// All policy/storage access is serialized on the main queue. Notification callbacks
/// recheck the desired request before scheduling and after asynchronous completion.
final class AlertController: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    @Published private(set) var archive = AlertArchive()
    @Published private(set) var permission = "Checking notification permission…"
    @Published private(set) var error: String?
    @Published private(set) var monitoring = false
    private var store: AlertStore?
    private var policy = AlertPolicy()
    private let center = UNUserNotificationCenter.current()
    private var desired: [String: AlertPlan] = [:]
    private var attempted = Set<String>()
    private var allowed = false
    private var permissionRevision = 0

    override init() {
        super.init()
        center.delegate = self
        // Runtime freshness never survives a process relaunch. Previously queued
        // requests are obsolete; already delivered items are reconciled separately.
        center.removeAllPendingNotificationRequests()
        do {
            var folder = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true).appendingPathComponent("GlucoseHistory", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete])
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try folder.setResourceValues(values)
            let candidate = AlertStore(url: folder.appendingPathComponent("alerts.json"))
            archive = try candidate.load()
            store = candidate
            policy = AlertPolicy(settings: archive.settings)
        } catch { self.error = "Alert storage unavailable; notifications are disabled. Existing data was kept. \(error)" }
        refreshPermission()
    }

    func configure(_ settings: AlertSettings) -> Bool {
        do {
            try settings.validate()
            guard let store else { throw AlertPolicyError.invalidArchive }
            var candidate = archive; candidate.settings = settings
            try store.save(candidate)
            archive = candidate
            try policy.configure(settings)
            monitoring = false
            error = nil
            reconcile()
            return true
        } catch { self.error = "Alert settings were not changed: \(error)"; return false }
    }
    func start(primary: SensorIdentity?) {
        policy.start(primary: primary, at: Date()); monitoring = primary != nil
        reconcile()
    }
    func stop() { policy.stop(); monitoring = false; reconcile() }
    func background() { policy.invalidateGlucose(); reconcile() }
    func invalidReading() { policy.invalidateGlucose(); reconcile() }
    func accept(_ record: GlucoseRecord) { policy.accept(record, now: Date()); reconcile() }
    func connectionChanged(_ connected: Bool) { policy.connection(connected, at: Date()); reconcile() }
    func tick(connected: Bool) {
        let now = Date(); policy.pulse(at: now); policy.connection(connected, at: now); reconcile()
    }
    func requestPermission() {
        center.requestAuthorization(options: [.alert, .sound]) { [weak self] _, failure in
            DispatchQueue.main.async {
                if let failure { self?.error = "Notification permission failed: \(failure.localizedDescription)" }
                self?.refreshPermission()
            }
        }
    }
    func refreshPermission() {
        permissionRevision += 1
        let revision = permissionRevision
        center.getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                guard let self, self.permissionRevision == revision else { return }
                let wasAllowed = self.allowed
                self.allowed = [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus)
                switch settings.authorizationStatus {
                case .notDetermined: self.permission = "Permission not requested"
                case .denied: self.permission = "Notifications denied — open iPhone Settings"
                case .authorized: self.permission = "Notifications authorized"
                case .provisional, .ephemeral: self.permission = "Notifications authorized with limited presentation"
                @unknown default: self.permission = "Notification permission unknown"
                }
                if self.allowed {
                    self.permission += settings.alertSetting == .enabled ? " · banners allowed" : " · banners disabled"
                    self.permission += settings.soundSetting == .enabled ? " · sound allowed" : " · sound disabled"
                }
                if self.allowed != wasAllowed { self.attempted = [] }
                self.reconcile()
            }
        }
        center.getDeliveredNotifications { [weak self] notifications in
            DispatchQueue.main.async {
                for notification in notifications { self?.observe(notification) }
            }
        }
    }

    private func reconcile() {
        let plans = store == nil ? [] : policy.plans(at: Date())
        let next = Dictionary(uniqueKeysWithValues: plans.map { ($0.id, $0) })
        let removed = desired.keys.filter { next[$0] == nil }
        center.removePendingNotificationRequests(withIdentifiers: Array(removed))
        center.removeDeliveredNotifications(withIdentifiers: Array(removed))
        for id in removed {
            if let plan = desired[id], attempted.contains(id) { _ = log(plan, .cancelled) }
            attempted.remove(id)
        }
        desired = next
        for plan in plans where !attempted.contains(plan.id) {
            attempted.insert(plan.id)
            guard allowed else { _ = log(plan, .permissionDenied); continue }
            schedule(plan)
        }
    }
    private func schedule(_ plan: AlertPlan) {
        // Check current OS settings for every new request, not just at app launch.
        center.getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                guard let self, self.isCurrent(plan) else { return }
                guard [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus) else {
                    _ = self.log(plan, .permissionDenied); self.refreshPermission(); return
                }
                guard self.log(plan, .requested) else { return }
                let content = UNMutableNotificationContent()
                content.title = self.title(plan.kind)
                let time = plan.evidenceAt.formatted(date: .abbreviated, time: .shortened)
                switch plan.kind {
                case .low, .high:
                    content.body = "A reading received at \(time) crossed your configured threshold. This alert may be delayed. Open GlucoTrail to check the latest reading."
                case .stale:
                    content.body = "Monitoring check scheduled from \(time). No newer eligible reading had arrived when scheduled. Open GlucoTrail; background readings are not reliable."
                case .disconnected:
                    content.body = "Connection check scheduled from \(time). The primary Bluetooth connection was absent when scheduled. Advertisement readings may still arrive. Open GlucoTrail to check."
                }
                content.sound = .default
                content.userInfo = ["kind": plan.kind.rawValue, "due": plan.due.timeIntervalSince1970]
                let delay = max(1, plan.due.timeIntervalSinceNow)
                let request = UNNotificationRequest(identifier: plan.id, content: content,
                    trigger: UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false))
                self.center.add(request) { [weak self] failure in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        guard self.isCurrent(plan) else {
                            self.center.removePendingNotificationRequests(withIdentifiers: [plan.id])
                            self.center.removeDeliveredNotifications(withIdentifiers: [plan.id])
                            return
                        }
                        _ = self.log(plan, failure == nil ? .acceptedBySystem : .failed)
                        if let failure { self.error = "Notification request failed: \(failure.localizedDescription)" }
                    }
                }
            }
        }
    }
    private func isCurrent(_ plan: AlertPlan) -> Bool {
        AlertRequestGate.permits(plan, desired: desired, at: Date())
    }
    @discardableResult private func log(_ plan: AlertPlan, _ status: AlertHistoryEntry.Status) -> Bool {
        saveEntry(AlertHistoryEntry(requestID: plan.id, kind: plan.kind, date: Date(), due: plan.due, status: status))
    }
    private func saveEntry(_ entry: AlertHistoryEntry) -> Bool {
        guard let store else { return false }
        do {
            var candidate = archive; candidate.append(entry)
            try store.save(candidate); archive = candidate
            return true
        } catch { self.error = "Alert history could not be saved: \(error)"; return false }
    }
    private func observe(_ notification: UNNotification) {
        let request = notification.request
        guard let raw = request.content.userInfo["kind"] as? String, let kind = AlertKind(rawValue: raw),
              let due = request.content.userInfo["due"] as? Double,
              !archive.history.contains(where: { $0.requestID == request.identifier && $0.status == .observedDelivered }) else { return }
        _ = saveEntry(AlertHistoryEntry(requestID: request.identifier, kind: kind, date: notification.date,
            due: Date(timeIntervalSince1970: due), status: .observedDelivered))
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self, let plan = self.desired[notification.request.identifier], self.isCurrent(plan) else {
                completionHandler([]); return
            }
            self.observe(notification)
            completionHandler([.banner, .sound, .list])
        }
    }
    func title(_ kind: AlertKind) -> String {
        switch kind {
        case .low: return "Low glucose threshold crossed"
        case .high: return "High glucose threshold crossed"
        case .stale: return "Check glucose reception"
        case .disconnected: return "Check sensor connection"
        }
    }
}
