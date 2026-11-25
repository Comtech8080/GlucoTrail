import Foundation

/// The UUID belongs to this iOS installation, not the sensor's Bluetooth MAC.
/// Advertised serials are identifiers, not cryptographic proof of ownership.
public struct SensorIdentity: Codable, Equatable {
    public let peripheralID: UUID
    public let serial: String
    public init(peripheralID: UUID, serial: String) throws {
        guard Advertisement.serial(from: "AiDEX X-" + serial) == serial.uppercased() else {
            throw IdentityError.invalidSerial
        }
        self.peripheralID = peripheralID
        self.serial = serial.uppercased()
    }
}

public enum IdentityError: Error { case invalidSerial, unsupportedVersion }

public final class SensorIdentityStore {
    private struct Record: Codable { let version: Int; let identity: SensorIdentity }
    private let defaults: UserDefaults
    private let key = "glucotrail.sensorIdentity.v1"
    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    public func load() throws -> SensorIdentity? {
        guard let data = defaults.data(forKey: key) else { return nil }
        let record = try JSONDecoder().decode(Record.self, from: data)
        guard record.version == 1 else { throw IdentityError.unsupportedVersion }
        return try SensorIdentity(peripheralID: record.identity.peripheralID, serial: record.identity.serial)
    }
    public func save(_ identity: SensorIdentity) throws {
        defaults.set(try JSONEncoder().encode(Record(version: 1, identity: identity)), forKey: key)
    }
    public func forget() { defaults.removeObject(forKey: key) }
}
