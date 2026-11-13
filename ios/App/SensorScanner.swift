import CoreBluetooth
import Foundation
import Combine
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
    @Published private(set) var sensors: [NearbySensor] = []
    @Published private(set) var status = "Tap Start scan to enable Bluetooth discovery."
    @Published private(set) var connectionStatus = "Not connected"
    @Published private(set) var scanning = false
    @Published private(set) var savedIdentity: SensorIdentity?
    @Published private(set) var packetCount = 0
    var selectedID: UUID? { savedIdentity?.peripheralID }
    var selected: NearbySensor? { sensors.first { $0.id == selectedID } }
    private var central: CBCentralManager?
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var session = ConnectionSession()
    private let store = SensorIdentityStore()
    private var timer: Timer?
    private var requested = false
    private var foreground = true
    // Cancellation is asynchronous. Never start another attempt on a peripheral
    // until its cancellation callback has drained, including after Stop/Start.
    private var cancelling = Set<UUID>()
    private let service = CBUUID(string: AidexProfile.service)

    override init() {
        super.init()
        do { savedIdentity = try store.load() }
        catch { status = "Saved sensor identity could not be read. Select your sensor again." }
    }

    func start() {
        guard !requested else { updateScan(); return }
        requested = true
        if let identity = savedIdentity { session.start(identity) }
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        if central == nil { central = CBCentralManager(delegate: self, queue: .main) }
        else { updateScan() }
    }

    func stop() {
        requested = false
        session.stop()
        timer?.invalidate()
        timer = nil
        cancelConnections()
        central?.stopScan()
        scanning = false
        connectionStatus = "Stopped — no automatic reconnection"
        status = "Scanning stopped. Displayed readings are historical."
    }

    func setForeground(_ active: Bool) {
        foreground = active
        if !active {
            session.suspend()
            cancelConnections()
            invalidateReadings()
            connectionStatus = "Paused while app is inactive"
        }
        updateScan()
    }

    func select(_ id: UUID) {
        guard let sensor = sensors.first(where: { $0.id == id }), let serial = sensor.serial,
              let identity = try? SensorIdentity(peripheralID: id, serial: serial) else {
            status = "A full AiDEX X serial is required before saving or connecting."
            return
        }
        do { try store.save(identity) }
        catch { status = "Could not save sensor identity."; return }
        session.stop()
        cancelConnections()
        savedIdentity = identity
        if requested { session.start(identity); tick() }
    }

    func clear() {
        stop()
        store.forget()
        savedIdentity = nil
        sensors = []
        // Keep cancelling peripheral references until their callbacks drain.
        peripherals = peripherals.filter { cancelling.contains($0.key) }
        packetCount = 0
    }

    private func invalidateReadings() {
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
            session.suspend()
            invalidateReadings()
            // CoreBluetooth invalidates pending connections on power loss.
            peripherals.removeAll()
            cancelling.removeAll()
            connectionStatus = "Waiting for Bluetooth"
        }
        updateScan()
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
        guard requested, foreground, let central, central.state == .poweredOn else { return }
        let now = Date()
        if session.timedOut(at: now), let id = selectedID {
            session.failed(id, at: now)
            if let peripheral = peripherals[id], peripheral.state != .disconnected {
                cancelling.insert(id)
                central.cancelPeripheralConnection(peripheral)
            }
        }
        if let id = selectedID, !cancelling.contains(id),
           session.phase == .waiting || session.phase == .retryWaiting {
            // Retrieval may be empty after OS cache loss. Scan for this UUID; never
            // silently adopt another UUID merely because the advertised serial matches.
            let peripheral = peripherals[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first
            if let peripheral, let target = session.begin(at: now), target == id {
                peripherals[id] = peripheral
                peripheral.delegate = self
                central.connect(peripheral, options: nil)
            }
        }
        switch session.phase {
        case .stopped: connectionStatus = "Select a sensor to connect"
        case .waiting: connectionStatus = "Searching for saved sensor; select again if its iOS identity changed"
        case .connecting: connectionStatus = "Connecting to saved sensor…"
        case .discovering: connectionStatus = "Connected — checking Bluetooth services…"
        case .connectedUnauthenticated: connectionStatus = "Connected; AiDEX profile found. Encrypted GATT readings are not enabled."
        case .retryWaiting: connectionStatus = "Connection lost or timed out. Retry \(session.failures)/5 pending."
        case .exhausted: connectionStatus = "Retry limit reached. Tap Stop, then Start scan to retry."
        case .unsupported: connectionStatus = "Expected AiDEX characteristics are missing. Connection closed."
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard !cancelling.contains(peripheral.identifier), foreground,
              session.connected(peripheral.identifier, at: Date()) else {
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
        if !cancelled { session.failed(peripheral.identifier, at: Date()) }
        if let i = sensors.firstIndex(where: { $0.id == peripheral.identifier }) { sensors[i].lastPacketValid = false }
        tick()
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral.identifier == selectedID, session.phase == .discovering,
              !cancelling.contains(peripheral.identifier) else { return }
        if error != nil { discoveryFailed(peripheral); return }
        guard let found = peripheral.services?.first(where: { $0.uuid == service }) else {
            finishDiscovery(peripheral, compatible: false); return
        }
        peripheral.discoverCharacteristics([CBUUID(string: AidexProfile.keyExchange),
            CBUUID(string: AidexProfile.session), CBUUID(string: AidexProfile.current)], for: found)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral.identifier == selectedID, session.phase == .discovering,
              !cancelling.contains(peripheral.identifier) else { return }
        if error != nil { discoveryFailed(peripheral); return }
        let found = Set((service.characteristics ?? []).map { $0.uuid })
        let required = Set([AidexProfile.keyExchange, AidexProfile.session, AidexProfile.current].map { CBUUID(string: $0) })
        finishDiscovery(peripheral, compatible: required.isSubset(of: found))
    }

    private func discoveryFailed(_ peripheral: CBPeripheral) {
        session.failed(peripheral.identifier, at: Date())
        cancelling.insert(peripheral.identifier)
        central?.cancelPeripheralConnection(peripheral)
        tick()
    }

    private func finishDiscovery(_ peripheral: CBPeripheral, compatible: Bool) {
        session.discovered(peripheral.identifier, compatible: compatible)
        if !compatible {
            cancelling.insert(peripheral.identifier)
            central?.cancelPeripheralConnection(peripheral)
        }
        // No setNotifyValue/readValue/writeValue: F003 requires authenticated KEY1.
        tick()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard scanning, foreground else { return }
        let now = Date()
        sensors.removeAll { $0.id != selectedID && now.timeIntervalSince($0.seenAt) > 120 }
        let retained = Set(sensors.map { $0.id }).union(cancelling)
        peripherals = peripherals.filter { retained.contains($0.key) || $0.key == selectedID }
        let id = peripheral.identifier
        guard sensors.contains(where: { $0.id == id }) || sensors.count < 50 || id == selectedID else { return }
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
        if id == selectedID, let serial = item.serial, serial != savedIdentity?.serial {
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
            } catch {
                item.lastPacketValid = false
                item.packetStatus = "Advertisement rejected: \(error)"
            }
        }
        if let index = sensors.firstIndex(where: { $0.id == id }) { sensors[index] = item }
        else { sensors.append(item) }
        tick()
    }
}
