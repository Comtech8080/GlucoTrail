import XCTest
@testable import GlucoTrailProtocol

final class SensorIdentityTests: XCTestCase {
    func testSaveRelaunchAndForget() throws {
        let suite = "GlucoTrailTests.\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let identity = try SensorIdentity(peripheralID: UUID(), serial: "22222abcde")
        let store = SensorIdentityStore(defaults: defaults)
        XCTAssertNil(try store.load())
        try store.save(identity)
        let relaunched = SensorIdentityStore(defaults: defaults)
        XCTAssertEqual(try relaunched.load(), identity)
        XCTAssertEqual(identity.serial, "22222ABCDE")
        relaunched.forget()
        XCTAssertNil(try store.load())
    }

    func testCorruptOrUnknownVersionNeverRestoresIdentity() throws {
        let suite = "GlucoTrailTests.\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = SensorIdentityStore(defaults: defaults)
        defaults.set(Data("corrupt".utf8), forKey: "glucotrail.sensorIdentity.v1")
        XCTAssertThrowsError(try store.load())
        let json = "{\"version\":2,\"identity\":{\"peripheralID\":\"\(UUID())\",\"serial\":\"22222ABCDE\"}}"
        defaults.set(Data(json.utf8), forKey: "glucotrail.sensorIdentity.v1")
        XCTAssertThrowsError(try store.load())
    }

    func testInvalidSerialCannotBeSavedAsIdentity() {
        for serial in ["", "short", "22222ABCDEextra", "22222AB CD", "22222ABCéE"] {
            XCTAssertThrowsError(try SensorIdentity(peripheralID: UUID(), serial: serial))
        }
    }
}
