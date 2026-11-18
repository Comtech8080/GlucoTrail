import CoreBluetooth
import Foundation
import Combine
import UIKit
import GlucoTrailProtocol

struct NearbySensor: Identifiable {
    let id: UUID
    var name: String
    var serial: String?
    var rssi: Int
    var seenAt: Date
    var timeline = ReadingTimeline()
    var packetStatus = "Waiting for a glucose packet"
    var lastPacketValid = false
}

/// Main-queue adapter. Radio connection is deliberately separate from glucose validation.
/// Phase 1 discovers the GATT profile but sends no authentication/activation writes.
final class SensorScanner: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    let alerts = AlertController()
    @Published private(set) var backgroundRecovery = UserDefaults.standard.bool(forKey: "glucotrail.backgroundRecovery")
    @Published private(set) var recoveryStatus = "Background recovery is off"
    @Published private(set) var sensors: [NearbySensor] = []
    @Published private(set) var status = "Tap Start scan to enable Bluetooth discovery."
    @Published private(set) var connectionStatus = "Not connected"
    @Published private(set) var scanning = false
    @Published private(set) var savedIdentity: SensorIdentity?
    @Published private(set) var packetCount = 0
    @Published private(set) var latestRecord: GlucoseRecord?
    @Published private(set) var recordValidatedThisSession = false
    @Published private(set) var historyRevision = 0
    @Published private(set) var historyIdentities: [SensorIdentity] = []
    @Published private(set) var storageError: String?
    @Published private(set) var pairState = SensorPairState()
    @Published private(set) var replacementRecord: GlucoseRecord?
    @Published private(set) var replacementValidatedThisSession = false
    @Published private(set) var replacementConnectionStatus = "No replacement selected"
    @Published private(set) var replacementReady = false
    @Published private(set) var automaticHandover = false
    @Published private(set) var pairMessage: String?
    private var pairStore: SensorPairStore?
    private var pairMonitor = SensorPairMonitor()
    var replacementIdentity: SensorIdentity? { pairState.replacement }
    private var monitoredIdentities: [SensorIdentity] { [savedIdentity, replacementIdentity].compactMap { $0 } }
    private var monitoredIDs: Set<UUID> { Set(monitoredIdentities.map { $0.peripheralID }) }
    private var history: GlucoseHistory?
    var selectedID: UUID? { savedIdentity?.peripheralID }
    var selected: NearbySensor? { sensors.first { $0.id == selectedID } }
    private var central: CBCentralManager?
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var sessions: [UUID: ConnectionSession] = [:]
    private let store = SensorIdentityStore()
    private var timer: Timer?
    private var requested = false
    private var foreground = UIApplication.shared.applicationState == .active
    // Cancellation is asynchronous. Never start another attempt on a peripheral
    // until its cancellation callback has drained, including after Stop/Start.
    private var cancelling = Set<UUID>()
    private let service = CBUUID(string: AidexProfile.service)

    override init() {
        super.init()
        do {
            var folder = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                .appendingPathComponent("GlucoseHistory", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try folder.setResourceValues(values)
            let roles = SensorPairStore(url: folder.appendingPathComponent("sensor-pair.json"))
            pairStore = roles
            let state: SensorPairState
            if let restored = try roles.load() { state = restored }
            else {
                state = SensorPairState(primary: try store.load())
                try roles.save(state)
            }
            pairState = state; pairMonitor = SensorPairMonitor(state: state)
            savedIdentity = state.primary
            history = try GlucoseHistory(url: folder.appendingPathComponent("readings.sqlite"))
            historyIdentities = try history!.identities()
            restoreLatest()
        } catch { storageError = "Local history is unavailable. Existing data was not erased. \(error)" }
        if !ProcessInfo.processInfo.arguments.contains("--demo"), UserDefaults.standard.bool(forKey: "glucotrail.centralCreated") {
            requested = backgroundRecovery && UserDefaults.standard.bool(forKey: "glucotrail.recoveryRequested") && history != nil && savedIdentity != nil
            reconcileSessions()
            if requested { alerts.start(primary: savedIdentity); installTimer() }
            createCentral()
        }
    }

    private func createCentral() {
        guard central == nil else { return }
        UserDefaults.standard.set(true, forKey: "glucotrail.centralCreated")
        central = CBCentralManager(delegate: self, queue: .main,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "com.glucotrail.primary-and-replacement"])
    }
    private func installTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
    }
    func configureAlerts(_ settings: AlertSettings) {
        if alerts.configure(settings), requested { alerts.start(primary: savedIdentity) }
    }
    func setBackgroundRecovery(_ enabled: Bool) {
        backgroundRecovery = enabled
        UserDefaults.standard.set(enabled, forKey: "glucotrail.backgroundRecovery")
        UserDefaults.standard.set(enabled && requested, forKey: "glucotrail.recoveryRequested")
        recoveryStatus = enabled ? "Experimental connection recovery enabled; background glucose is unavailable" : "Background recovery is off"
        if !foreground && !enabled {
            for id in sessions.keys { sessions[id]?.suspend() }
            cancelConnections()
        }
    }
    private func mayRecover(_ id: UUID) -> Bool {
        BackgroundRecoveryPolicy.mayRecover(enabled: backgroundRecovery, requested: requested,
            storageAvailable: history != nil && storageError == nil, identity: id, selected: monitoredIDs)
    }
    private func recoverConnections() {
        guard !foreground, let central, central.state == .poweredOn else { return }
        for identity in monitoredIdentities where mayRecover(identity.peripheralID) {
            let id = identity.peripheralID
            guard !cancelling.contains(id), let peripheral = peripherals[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first,
                  sessions[id]?.begin(at: Date()) == id else { continue }
            peripherals[id] = peripheral; peripheral.delegate = self
            central.connect(peripheral, options: nil)
            recoveryStatus = "Background connection requested; glucose reception requires the foreground"
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        for peripheral in dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? [] {
            let id = peripheral.identifier
            guard mayRecover(id), let identity = monitoredIdentities.first(where: { $0.peripheralID == id }) else {
                central.cancelPeripheralConnection(peripheral); continue
            }
            peripherals[id] = peripheral; peripheral.delegate = self
            sessions[id] = ConnectionSession(); sessions[id]?.start(identity)
            _ = sessions[id]?.begin(at: Date())
            if peripheral.state == .connected {
                _ = sessions[id]?.connected(id, at: Date())
                peripheral.discoverServices([service])
            } else if peripheral.state != .connecting { sessions[id]?.suspend() }
        }
        invalidateReadings()
        recoveryStatus = "Bluetooth state restored; waiting for new foreground readings"
        central.stopScan()
        recoverConnections()
    }

    func historyRecords(for identity: SensorIdentity, from start: Date, through end: Date) throws -> [GlucoseRecord] {
        guard let history else { throw HistoryError.database("History is unavailable") }
        return try history.records(for: identity, from: start, through: end)
    }

    private func restoreLatest() {
        latestRecord = nil
        recordValidatedThisSession = false
        replacementRecord = nil
        replacementValidatedThisSession = false
        guard let history else { return }
        do {
            if let identity = savedIdentity { latestRecord = try history.latest(for: identity) }
            if let identity = replacementIdentity { replacementRecord = try history.latest(for: identity) }
        }
        catch { storageError = "Could not read saved glucose history. \(error)" }
    }

    func start() {
        guard !requested else { updateScan(); return }
        requested = true
        UserDefaults.standard.set(backgroundRecovery, forKey: "glucotrail.recoveryRequested")
        alerts.start(primary: savedIdentity)
        pairMonitor.beginMonitoring()
        reconcileSessions()
        installTimer()
        if central == nil { createCentral() }
        else { updateScan() }
    }

    func stop() {
        requested = false
        UserDefaults.standard.set(false, forKey: "glucotrail.recoveryRequested")
        alerts.stop()
        invalidateReadings()
        for id in sessions.keys { sessions[id]?.stop() }
        timer?.invalidate()
        timer = nil
        cancelConnections()
        central?.stopScan()
        scanning = false
        connectionStatus = "Stopped — no automatic reconnection"
        replacementConnectionStatus = "Stopped — no automatic reconnection"
        status = "Scanning stopped. Displayed readings are historical."
    }

    func setForeground(_ active: Bool) {
        let resumed = active && !foreground
        foreground = active
        if active { alerts.refreshPermission() }
        if resumed && requested { pairMonitor.beginMonitoring(); alerts.start(primary: savedIdentity) }
        if !active {
            if !backgroundRecovery {
                for id in sessions.keys { sessions[id]?.suspend() }
                cancelConnections()
            }
            invalidateReadings()
            alerts.background()
            connectionStatus = "Glucose reception paused while app is inactive"
        }
        updateScan()
        if !active { recoverConnections() }
    }

    func select(_ id: UUID) {
        guard let identity = discoveredIdentity(id) else { return }
        if identity == savedIdentity { return }
        guard savedIdentity == nil else {
            pairMessage = "Use the Replacement tab to change primary, or explicitly forget both roles first."
            return
        }
        var candidate = pairMonitor
        candidate.selectPrimary(identity, at: Date())
        commitPair(candidate)
    }

    func selectReplacement(_ id: UUID) {
        guard let identity = discoveredIdentity(id) else { return }
        do {
            var candidate = pairMonitor
            try candidate.selectReplacement(identity, at: Date())
            commitPair(candidate)
        } catch { pairMessage = "Choose a different sensor UUID and serial from the primary." }
    }

    private func discoveredIdentity(_ id: UUID) -> SensorIdentity? {
        guard let sensor = sensors.first(where: { $0.id == id }), let serial = sensor.serial,
              let identity = try? SensorIdentity(peripheralID: id, serial: serial) else {
            pairMessage = "A complete AiDEX X serial is required."
            return nil
        }
        return identity
    }

    private func commitPair(_ candidate: SensorPairMonitor) {
        do {
            guard let pairStore else { throw PairError.invalidState }
            try pairStore.save(candidate.state)
            let priorPrimary = savedIdentity, priorReplacement = replacementIdentity
            let primaryValid = recordValidatedThisSession, replacementValid = replacementValidatedThisSession
            pairMonitor = candidate
            pairState = candidate.state
            savedIdentity = pairState.primary
            if savedIdentity != priorPrimary {
                if requested { alerts.start(primary: savedIdentity) } else { alerts.stop() }
            }
            // Legacy phase-1 identity is no longer authoritative after migration.
            store.forget()
            restoreLatest()
            if savedIdentity == priorPrimary { recordValidatedThisSession = primaryValid }
            else if savedIdentity == priorReplacement { recordValidatedThisSession = replacementValid }
            if replacementIdentity == priorReplacement { replacementValidatedThisSession = replacementValid }
            reconcileSessions()
            automaticHandover = pairMonitor.automatic
            replacementReady = pairMonitor.replacementReady(at: Date())
            historyRevision += 1
            pairMessage = "Sensor roles saved. Prior readings remain in History."
        } catch {
            pairMonitor.setAutomatic(false)
            automaticHandover = false
            pairMessage = "Sensor roles were not changed: \(error)"
        }
    }

    func removeReplacement() {
        var candidate = pairMonitor
        candidate.removeReplacement(at: Date())
        commitPair(candidate)
    }

    func setAutomaticHandover(_ enabled: Bool) {
        pairMonitor.setAutomatic(enabled)
        automaticHandover = pairMonitor.automatic
    }

    func promoteReplacement(automatically: Bool = false) {
        do {
            var candidate = pairMonitor
            try candidate.promote(at: Date(), automatically: automatically)
            commitPair(candidate)
        } catch { pairMessage = "Replacement not ready. Wait for three consecutive new post-warm-up readings." }
    }

    func clear() {
        stop()
        var candidate = pairMonitor
        candidate.forget(at: Date())
        commitPair(candidate)
        sensors = []
        peripherals = peripherals.filter { cancelling.contains($0.key) }
        packetCount = 0
    }

    private func reconcileSessions() {
        for id in Array(sessions.keys) where !monitoredIDs.contains(id) {
            sessions[id] = nil
            if let peripheral = peripherals[id], peripheral.state != .disconnected {
                cancelling.insert(id)
                central?.cancelPeripheralConnection(peripheral)
            }
        }
        for identity in monitoredIdentities {
            if sessions[identity.peripheralID] == nil { sessions[identity.peripheralID] = ConnectionSession() }
            if requested && sessions[identity.peripheralID]?.requested != true {
                sessions[identity.peripheralID]?.start(identity)
            }
        }
    }

    private func invalidateReadings() {
        recordValidatedThisSession = false
        replacementValidatedThisSession = false
        replacementReady = false
        automaticHandover = false
        pairMonitor.suspend()
        for i in sensors.indices { sensors[i].lastPacketValid = false }
    }

    private func cancelConnections() {
        guard let central else { return }
        for peripheral in peripherals.values where peripheral.state != .disconnected {
            cancelling.insert(peripheral.identifier)
            central.cancelPeripheralConnection(peripheral)
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn {
            for id in sessions.keys { sessions[id]?.suspend() }
            invalidateReadings()
            // CoreBluetooth invalidates pending connections on power loss.
            peripherals.removeAll()
            cancelling.removeAll()
            connectionStatus = "Waiting for Bluetooth"
            alerts.invalidReading()
            alerts.connectionChanged(false)
        }
        if central.state == .poweredOn && requested && foreground { pairMonitor.beginMonitoring() }
        updateScan()
        recoverConnections()
    }

    private func updateScan() {
        guard let central else { return }
        if central.state == .poweredOn { central.stopScan() }
        scanning = false
        guard requested else { return }
        guard foreground else {
            status = "Paused while GlucoTrail is inactive. Reopen the app to resume."
            return
        }
        switch central.state {
        case .poweredOn:
            central.scanForPeripherals(withServices: [service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
            scanning = true
            status = "Scanning for service 181F. Select the sensor whose serial matches yours."
            tick()
        case .poweredOff: status = "Bluetooth is off. Enable it in iPhone Settings."
        case .unauthorized: status = "Bluetooth access denied. Allow GlucoTrail in iPhone Settings."
        case .unsupported: status = "Bluetooth requires a supported physical iPhone. Simulator supports demo only."
        case .resetting: status = "Bluetooth is resetting. Waiting to resume…"
        case .unknown: status = "Waiting for Bluetooth permission and availability…"
        @unknown default: status = "Bluetooth is unavailable."
        }
    }

    private func tick() {
        guard requested, foreground else { return }
        alerts.tick(connected: central?.state == .poweredOn && selectedID.flatMap { peripherals[$0]?.state } == .connected)
        guard let central, central.state == .poweredOn else { return }
        let now = Date()
        pairMonitor.pulse(at: now)
        automaticHandover = pairMonitor.automatic
        for identity in monitoredIdentities {
            let id = identity.peripheralID
            if sessions[id]?.timedOut(at: now) == true {
                sessions[id]?.failed(id, at: now)
                if let peripheral = peripherals[id], peripheral.state != .disconnected {
                    cancelling.insert(id)
                    central.cancelPeripheralConnection(peripheral)
                }
            }
            if !cancelling.contains(id), sessions[id]?.phase == .waiting || sessions[id]?.phase == .retryWaiting {
                let peripheral = peripherals[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first
                if let peripheral, sessions[id]?.begin(at: now) == id {
                    peripherals[id] = peripheral
                    peripheral.delegate = self
                    central.connect(peripheral, options: nil)
                }
            }
        }
        connectionStatus = connectionText(savedIdentity)
        replacementConnectionStatus = connectionText(replacementIdentity)
        replacementReady = pairMonitor.replacementReady(at: now)
        if pairMonitor.shouldAutomaticallyPromote(at: now) { promoteReplacement(automatically: true) }
    }

    private func connectionText(_ identity: SensorIdentity?) -> String {
        guard let identity, let session = sessions[identity.peripheralID] else { return "No sensor selected" }
        switch session.phase {
        case .stopped: return "Reception stopped"
        case .waiting: return "Searching for saved sensor"
        case .connecting: return "Connecting…"
        case .discovering: return "Connected — checking services…"
        case .connectedUnauthenticated: return "Connected; encrypted GATT remains disabled."
        case .retryWaiting: return "Retry \(session.failures)/5 pending"
        case .exhausted: return "Retry limit reached. Stop and Start to retry."
        case .unsupported: return "AiDEX characteristics missing. Connection closed."
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard !cancelling.contains(peripheral.identifier), foreground || mayRecover(peripheral.identifier),
              sessions[peripheral.identifier]?.connected(peripheral.identifier, at: Date()) == true else {
            cancelling.insert(peripheral.identifier)
            central.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.delegate = self
        peripheral.discoverServices([service])
        tick()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectionEnded(peripheral)
    }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectionEnded(peripheral)
    }
    private func connectionEnded(_ peripheral: CBPeripheral) {
        let cancelled = cancelling.remove(peripheral.identifier) != nil
        if !cancelled { sessions[peripheral.identifier]?.failed(peripheral.identifier, at: Date()) }
        if let i = sensors.firstIndex(where: { $0.id == peripheral.identifier }) { sensors[i].lastPacketValid = false }
        if peripheral.identifier == selectedID { recordValidatedThisSession = false }
        if peripheral.identifier == selectedID { alerts.invalidReading() }
        if peripheral.identifier == selectedID { alerts.connectionChanged(false) }
        if peripheral.identifier == replacementIdentity?.peripheralID { replacementValidatedThisSession = false }
        if let identity = monitoredIdentities.first(where: { $0.peripheralID == peripheral.identifier }) { pairMonitor.reject(identity) }
        tick()
        if !foreground, mayRecover(peripheral.identifier) {
            let delay = max(0, sessions[peripheral.identifier]?.retryAt?.timeIntervalSinceNow ?? 0)
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in self?.recoverConnections() }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard monitoredIDs.contains(peripheral.identifier), sessions[peripheral.identifier]?.phase == .discovering,
              !cancelling.contains(peripheral.identifier) else { return }
        if error != nil { discoveryFailed(peripheral); return }
        guard let found = peripheral.services?.first(where: { $0.uuid == service }) else {
            finishDiscovery(peripheral, compatible: false); return
        }
        peripheral.discoverCharacteristics([CBUUID(string: AidexProfile.keyExchange),
            CBUUID(string: AidexProfile.session), CBUUID(string: AidexProfile.current)], for: found)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard monitoredIDs.contains(peripheral.identifier), sessions[peripheral.identifier]?.phase == .discovering,
              !cancelling.contains(peripheral.identifier) else { return }
        if error != nil { discoveryFailed(peripheral); return }
        let found = Set((service.characteristics ?? []).map { $0.uuid })
        let required = Set([AidexProfile.keyExchange, AidexProfile.session, AidexProfile.current].map { CBUUID(string: $0) })
        finishDiscovery(peripheral, compatible: required.isSubset(of: found))
    }

    private func discoveryFailed(_ peripheral: CBPeripheral) {
        sessions[peripheral.identifier]?.failed(peripheral.identifier, at: Date())
        cancelling.insert(peripheral.identifier)
        central?.cancelPeripheralConnection(peripheral)
        tick()
    }

    private func finishDiscovery(_ peripheral: CBPeripheral, compatible: Bool) {
        sessions[peripheral.identifier]?.discovered(peripheral.identifier, compatible: compatible)
        if peripheral.identifier == selectedID { alerts.connectionChanged(compatible) }
        if !compatible {
            cancelling.insert(peripheral.identifier)
            central?.cancelPeripheralConnection(peripheral)
        }
        // No setNotifyValue/readValue/writeValue: F003 requires authenticated KEY1.
        tick()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard scanning, BackgroundRecoveryPolicy.mayReadGlucose(foreground: foreground, requested: requested) else { return }
        let now = Date()
        sensors.removeAll { !monitoredIDs.contains($0.id) && now.timeIntervalSince($0.seenAt) > 120 }
        let retained = Set(sensors.map { $0.id }).union(cancelling)
        peripherals = peripherals.filter { retained.contains($0.key) || monitoredIDs.contains($0.key) }
        let id = peripheral.identifier
        guard sensors.contains(where: { $0.id == id }) || sensors.count < 50 || monitoredIDs.contains(id) else { return }
        peripherals[id] = peripheral
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name
        var item = sensors.first { $0.id == id } ?? NearbySensor(
            id: id, name: name ?? "Unnamed 181F device", serial: nil, rssi: RSSI.intValue, seenAt: now)
        if let name {
            item.name = name
            if let serial = Advertisement.serial(from: name) { item.serial = serial }
        }
        item.rssi = RSSI.intValue
        item.seenAt = now
        let monitoredIdentity = monitoredIdentities.first { $0.peripheralID == id }
        if let identity = monitoredIdentity, let serial = item.serial, serial != identity.serial {
            stop()
            status = "Saved UUID now advertises a different serial. Forget it and verify the sensor before selecting again."
            item.lastPacketValid = false
            item.packetStatus = "Identity mismatch — rejected"
        } else if let data = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data {
            packetCount += 1
            do {
                let reading = try Advertisement.decode(manufacturerData: data)
                let accepted = item.timeline.accept(reading, at: now)
                // An old or duplicate packet cannot clear a previous invalid/stopped state.
                if accepted { item.lastPacketValid = true }
                item.packetStatus = accepted ? "Advertisement CRC verified · new sensor minute" : "Duplicate or older minute — age unchanged"
                if let identity = monitoredIdentity {
                    do {
                        guard let history else { throw HistoryError.database("History is unavailable") }
                        if try history.insert(reading, identity: identity, receivedAt: now) {
                            let record = GlucoseRecord(identity: identity, reading: reading, receivedAt: now)
                            pairMonitor.accept(record)
                            if identity == savedIdentity {
                                latestRecord = record; recordValidatedThisSession = true
                            } else {
                                replacementRecord = record; replacementValidatedThisSession = true
                            }
                            storageError = nil
                            historyIdentities = try history.identities()
                            historyRevision += 1
                            if identity == savedIdentity { alerts.accept(record) }
                            item.packetStatus = "New sensor minute saved locally"
                        } else {
                            item.packetStatus = "Already saved or older sensor minute — receipt time unchanged"
                        }
                    } catch {
                        if id == selectedID { recordValidatedThisSession = false }
                        if id == selectedID { alerts.invalidReading() }
                        else { replacementValidatedThisSession = false }
                        pairMonitor.reject(identity)
                        storageError = "Reading could not be saved. Existing history was kept. \(error)"
                        item.packetStatus = "History write failed"
                    }
                }
            } catch {
                item.lastPacketValid = false
                if id == selectedID { recordValidatedThisSession = false }
                if id == selectedID { alerts.invalidReading() }
                if id == replacementIdentity?.peripheralID { replacementValidatedThisSession = false }
                if let identity = monitoredIdentity { pairMonitor.reject(identity) }
                item.packetStatus = "Advertisement rejected: \(error)"
            }
        }
        if let index = sensors.firstIndex(where: { $0.id == id }) { sensors[index] = item }
        else { sensors.append(item) }
        tick()
    }
}
