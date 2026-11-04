import XCTest
@testable import GlucoTrailProtocol

final class AdvertisementTests: XCTestCase {
    private let vector: [UInt8] = [0x3F,0,0,0,0x0A,0x7E,0x81,0x64,0x7B,0x81,0x64,0x7C,0x81,0x64,0,0,0x5F,0xD3,0,0x83]

    func testAndroidReferenceVector() throws {
        let result = try Advertisement.decode(manufacturerData: Data([0x59, 0] + vector))
        XCTAssertEqual(result.minute, 63)
        XCTAssertEqual(result.glucoseMgDl, 382)
        XCTAssertEqual(result.trendRaw, 10)
        XCTAssertEqual(result.quality, 100)
        XCTAssertEqual(result.previousMgDl, 379)
        XCTAssertEqual(result.previous2MgDl, 380)
        XCTAssertEqual(result.mmolL, 382.0 / 18, accuracy: 0.0001)
    }

    func testTruncationWrongCompanyAndCorruption() {
        for size in 0..<20 { XCTAssertThrowsError(try Advertisement.decodePayload(Data(vector.prefix(size)))) }
        XCTAssertThrowsError(try Advertisement.decodePayload(Data(vector + [0])))
        XCTAssertThrowsError(try Advertisement.decode(manufacturerData: Data([0x4C, 0] + vector)))
        for index in vector.indices {
            var bad = vector
            bad[index] ^= 1
            XCTAssertThrowsError(try Advertisement.decodePayload(Data(bad)))
        }
    }

    private func packet(packed: Int = 0x817E, trend: UInt8 = 0x0A, quality: UInt8 = 100) -> Data {
        var b = vector
        b[4] = trend; b[5] = UInt8(packed & 255); b[6] = UInt8(packed >> 8); b[7] = quality
        var sum: UInt64 = 0
        for offset in stride(from: 0, to: 16, by: 4) {
            for j in 0..<4 { sum += UInt64(b[offset + j]) << (8 * j) }
        }
        let crc = Advertisement.crc32(Array(b.prefix(16)), seed: UInt32(sum % 0x7FA777))
        for j in 0..<4 { b[16 + j] = UInt8((crc >> (8 * j)) & 255) }
        return Data(b)
    }

    func testSignedTrendAndInvalidFlags() throws {
        XCTAssertEqual(try Advertisement.decodePayload(packet(trend: 0xF6)).trendRaw, -10)
        for packed in [0x017E, 0x857E, 0x8000, 0x83FF] {
            XCTAssertThrowsError(try Advertisement.decodePayload(packet(packed: packed)))
        }
        XCTAssertThrowsError(try Advertisement.decodePayload(packet(quality: 0)))
    }

    func testNameDoesNotInventSensorIdentity() {
        XCTAssertEqual(Advertisement.serial(from: "AiDEX X-22222abcde"), "22222ABCDE")
        for name in ["Other-22222ABCDE", "AiDEX X-", "AiDEX X-a b", "AiDEX X-é"] {
            XCTAssertNil(Advertisement.serial(from: name))
        }
        XCTAssertNil(Advertisement.serial(from: nil))
    }

    func testDuplicatePacketsDoNotRefreshAge() throws {
        let reading = try Advertisement.decodePayload(Data(vector))
        let start = Date(timeIntervalSince1970: 1000)
        var timeline = ReadingTimeline()
        XCTAssertTrue(timeline.isStale(at: start))
        XCTAssertTrue(timeline.accept(reading, at: start))
        XCTAssertFalse(timeline.accept(reading, at: start.addingTimeInterval(299)))
        XCTAssertFalse(timeline.isStale(at: start.addingTimeInterval(299)))
        XCTAssertTrue(timeline.isStale(at: start.addingTimeInterval(300)))
        XCTAssertEqual(timeline.receivedAt, start)
    }
}
