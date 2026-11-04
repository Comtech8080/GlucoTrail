import Foundation

public struct SensorReading: Equatable {
    public let minute: Int
    public let glucoseMgDl: Int
    public let trendRaw: Int
    public let quality: Int
    public let previousMgDl: Int?
    public let previous2MgDl: Int?
    public var mmolL: Double { Double(glucoseMgDl) / 18.0 }
}

public enum PacketError: Error, Equatable {
    case wrongCompany, wrongLength, checksum, invalidReading, warmup, lowQuality
}

/// Port of Android's AidexAdvertisementParser. iOS includes the two-byte company ID.
public enum Advertisement {
    public static func decode(manufacturerData: Data) throws -> SensorReading {
        let bytes = [UInt8](manufacturerData)
        guard bytes.count >= 2 else { throw PacketError.wrongLength }
        guard bytes[0] == 0x59, bytes[1] == 0 else { throw PacketError.wrongCompany }
        return try decodePayload(Data(bytes.dropFirst(2)))
    }

    public static func decodePayload(_ payload: Data) throws -> SensorReading {
        let b = [UInt8](payload)
        guard b.count == 20 else { throw PacketError.wrongLength }
        let seed = (UInt64(le32(b, 0)) + UInt64(le32(b, 4)) + UInt64(le32(b, 8)) + UInt64(le32(b, 12))) % 0x7FA777
        guard crc32(Array(b.prefix(16)), seed: UInt32(seed)) == le32(b, 16) else { throw PacketError.checksum }
        let packed = le16(b, 5)
        let glucose = packed & 0x03FF
        guard packed & 0x0400 == 0 else { throw PacketError.warmup }
        guard packed & 0x8000 != 0, (18...800).contains(glucose) else { throw PacketError.invalidReading }
        guard b[7] > 0 else { throw PacketError.lowQuality }
        func previous(_ offset: Int) -> Int? {
            let value = le16(b, offset)
            let glucose = value & 0x03FF
            return value & 0x8000 != 0 && value & 0x0400 == 0 && (18...800).contains(glucose) ? glucose : nil
        }
        return SensorReading(minute: le16(b, 0), glucoseMgDl: glucose,
            trendRaw: Int(Int8(bitPattern: b[4])), quality: Int(b[7]),
            previousMgDl: previous(8), previous2MgDl: previous(11))
    }

    public static func serial(from name: String?) -> String? {
        guard let name, name.uppercased().hasPrefix("AIDEX X-") else { return nil }
        let suffix = String(name.dropFirst(8)).uppercased()
        guard !suffix.isEmpty, suffix.count <= 32,
              suffix.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) }) else { return nil }
        return suffix
    }

    static func crc32(_ bytes: [UInt8], seed: UInt32) -> UInt32 {
        var crc = seed
        for byte in bytes {
            crc ^= UInt32(byte) << 24
            for _ in 0..<8 {
                crc = crc & 0x80000000 != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1
            }
        }
        return crc
    }

    private static func le16(_ b: [UInt8], _ i: Int) -> Int { Int(b[i]) | Int(b[i + 1]) << 8 }
    private static func le32(_ b: [UInt8], _ i: Int) -> UInt32 {
        UInt32(b[i]) | UInt32(b[i + 1]) << 8 | UInt32(b[i + 2]) << 16 | UInt32(b[i + 3]) << 24
    }
}

/// Tracks receipt of a new sensor minute, not reception of repeated cached advertisements.
public struct ReadingTimeline {
    public private(set) var reading: SensorReading?
    public private(set) var receivedAt: Date?
    public init() {}

    @discardableResult
    public mutating func accept(_ value: SensorReading, at date: Date) -> Bool {
        guard reading == nil || value.minute > reading!.minute else { return false }
        reading = value
        receivedAt = date
        return true
    }

    public func isStale(at date: Date) -> Bool {
        guard let receivedAt else { return true }
        return date.timeIntervalSince(receivedAt) >= 5 * 60
    }
}
