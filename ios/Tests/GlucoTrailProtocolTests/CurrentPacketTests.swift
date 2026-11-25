import XCTest
@testable import GlucoTrailProtocol

final class CurrentPacketTests: XCTestCase {
    // Independently calculated fixed CRC, matching Android AidexF003ParserTest fields.
    private let body: [UInt8] = [1, 0, 0, 244, 210, 4, 225, 128, 0, 0, 0, 0, 0, 0, 0]
    private func packet(packed: Int = 0x80E1) -> Data {
        var bytes = body
        bytes[6] = UInt8(packed & 255); bytes[7] = UInt8(packed >> 8)
        let crc = CurrentPacket.crc16(bytes)
        return Data(bytes + [UInt8(crc & 255), UInt8(crc >> 8)])
    }
    func testAndroidClearPacketFields() throws {
        let result = try CurrentPacket.decodeDecrypted(Data(body + [0x0B, 0x07]))
        XCTAssertEqual(result.minute, 1234)
        XCTAssertEqual(result.glucoseMgDl, 225)
        XCTAssertEqual(result.trendRaw, -12)
        XCTAssertEqual(result.quality, 100)
    }
    func testCRCStandardCheckVector() {
        XCTAssertEqual(CurrentPacket.crc16(Array("123456789".utf8)), 0x29B1)
    }
    func testEncryptedBoundaryRejectsEvenValidClearData() {
        XCTAssertThrowsError(try CurrentPacket.decodeWire(packet())) { error in
            XCTAssertTrue(error is GATTDecodeError)
        }
        XCTAssertThrowsError(try CurrentPacket.decodeWire(Data()))
    }
    func testTruncatedExtendedAndCorruptPackets() {
        let valid = packet()
        for length in 0..<17 { XCTAssertThrowsError(try CurrentPacket.decodeDecrypted(Data(valid.prefix(length)))) }
        XCTAssertThrowsError(try CurrentPacket.decodeDecrypted(valid + Data([0])))
        for index in valid.indices {
            var corrupt = valid; corrupt[index] ^= 1
            XCTAssertThrowsError(try CurrentPacket.decodeDecrypted(corrupt))
        }
    }
    func testWarmupInvalidFlagAndBoundsRejected() {
        for packed in [0x84E1, 0x00E1, 0x8000, 0x8011, 0x8321, 0x83FF] {
            XCTAssertThrowsError(try CurrentPacket.decodeDecrypted(packet(packed: packed)))
        }
    }
}
