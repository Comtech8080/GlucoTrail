import Foundation

/// UUIDs copied from Android AidexProtocol, not inferred from standard CGM services.
public enum AidexProfile {
    public static let service = "181F"
    public static let keyExchange = "F001"
    public static let session = "F002"
    public static let current = "F003"
}

/// Explicit boundary: wire F003 is encrypted. A CRC alone does not authenticate it.
public enum GATTDecodeError: Error { case authenticationNotValidated }
public enum CurrentPacket {
    public static func decodeWire(_ encrypted: Data) throws -> SensorReading {
        throw GATTDecodeError.authenticationNotValidated
    }

    /// Port of AidexF003Parser.parseClearWithCrc. ONLY for already decrypted data.
    /// Never call this directly from a CoreBluetooth characteristic callback.
    public static func decodeDecrypted(_ clear: Data) throws -> SensorReading {
        let b = [UInt8](clear)
        guard b.count == 17 else { throw PacketError.wrongLength }
        guard crc16(Array(b.prefix(15))) == (UInt16(b[15]) | UInt16(b[16]) << 8) else { throw PacketError.checksum }
        let packed = Int(b[6]) | Int(b[7]) << 8
        let glucose = packed & 0x03FF
        guard packed & 0x0400 == 0 else { throw PacketError.warmup }
        guard packed & 0x8000 != 0, (18...800).contains(glucose) else { throw PacketError.invalidReading }
        return SensorReading(minute: Int(b[4]) | Int(b[5]) << 8, glucoseMgDl: glucose,
                             trendRaw: Int(Int8(bitPattern: b[3])), quality: 100,
                             previousMgDl: nil, previous2MgDl: nil)
    }

    static func crc16(_ bytes: [UInt8]) -> UInt16 {
        var crc: UInt16 = 0xFFFF
        for byte in bytes {
            crc ^= UInt16(byte) << 8
            for _ in 0..<8 { crc = crc & 0x8000 != 0 ? (crc << 1) ^ 0x1021 : crc << 1 }
        }
        return crc
    }
}
