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

/// All callbacks and published state run on the main queue. No connection or sensor writes.
final class SensorScanner: NSObject, ObservableObject, CBCentralManagerDelegate {
    @Published private(set) var sensors: [NearbySensor] = []
    @Published private(set) var status = "Tap Start scan to enable Bluetooth discovery."
    @Published private(set) var scanning = false
    @Published private(set) var selectedID: UUID?
    @Published private(set) var packetCount = 0
    private var central: CBCentralManager?
    private var requested = false
    private var foreground = true
    private let service = CBUUID(string: "181F")

    var selected: NearbySensor? { sensors.first { $0.id == selectedID } }

    func start() {
        requested = true
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        } else { updateScan() }
    }

    func stop() {
        requested = false
        central?.stopScan()
        scanning = false
        status = "Scanning stopped. Displayed readings are historical."
    }

    func setForeground(_ active: Bool) {
        foreground = active
        updateScan()
    }

    func select(_ id: UUID) { selectedID = id }

    func clear() {
        stop()
        sensors = []
        selectedID = nil
        packetCount = 0
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) { updateScan() }

    private func updateScan() {
        guard let central else { return }
        central.stopScan()
        scanning = false
        guard requested else { return }
        guard foreground else {
            status = "Paused while GlucoTrail is not active. Reopen the app to resume."
            return
        }
        switch central.state {
        case .poweredOn:
            central.scanForPeripherals(withServices: [service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
            scanning = true
            status = "Scanning for AiDEX sensors advertising service 181F…"
        case .poweredOff: status = "Bluetooth is off. Enable it in iPhone Settings."
        case .unauthorized: status = "Bluetooth access was denied. Allow GlucoTrail in iPhone Settings → Privacy & Security → Bluetooth."
        case .unsupported: status = "Bluetooth scanning requires a supported physical iPhone. Use the demo in Simulator."
        case .resetting: status = "Bluetooth is resetting. Waiting to resume…"
        case .unknown: status = "Waiting for Bluetooth permission and availability…"
        @unknown default: status = "Bluetooth is unavailable."
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard scanning, foreground else { return }
        let now = Date()
        // Bound nearby discovery state; retain the selected sensor even when temporarily absent.
        sensors.removeAll { $0.id != selectedID && now.timeIntervalSince($0.seenAt) > 120 }
        let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name
        var item = sensors.first { $0.id == peripheral.identifier } ?? NearbySensor(
            id: peripheral.identifier, name: localName ?? "Unnamed 181F device", serial: nil,
            rssi: RSSI.intValue, seenAt: now)
        if let localName {
            item.name = localName
            if let serial = Advertisement.serial(from: localName) { item.serial = serial }
        }
        item.rssi = RSSI.intValue
        item.seenAt = now
        if let data = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data {
            packetCount += 1
            do {
                let reading = try Advertisement.decode(manufacturerData: data)
                let accepted = item.timeline.accept(reading, at: now)
                item.lastPacketValid = true
                item.packetStatus = accepted ? "CRC verified · new sensor minute" : "CRC verified · duplicate or older sensor minute"
            } catch let error as PacketError {
                item.lastPacketValid = false
                switch error {
                case .wrongCompany: item.packetStatus = "Not an AiDEX manufacturer packet"
                case .wrongLength: item.packetStatus = "No full glucose packet in this advertisement (\(data.count) bytes)"
                case .checksum: item.packetStatus = "Checksum failed — packet rejected"
                case .warmup: item.packetStatus = "Sensor reports warm-up — no current reading"
                case .invalidReading: item.packetStatus = "Sensor reports an invalid reading"
                case .lowQuality: item.packetStatus = "Sensor reports zero quality — packet rejected"
                }
            } catch {
                item.lastPacketValid = false
                item.packetStatus = "Packet could not be decoded"
            }
        }
        if let index = sensors.firstIndex(where: { $0.id == item.id }) { sensors[index] = item }
        else if sensors.count < 50 { sensors.append(item) }
    }
}
