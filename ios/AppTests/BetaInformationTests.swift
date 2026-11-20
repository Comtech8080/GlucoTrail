import XCTest
@testable import GlucoTrail

final class BetaInformationTests: XCTestCase {
    func testBuildComesFromBundleAndHandlesMissingMetadata() {
        XCTAssertEqual(AppBuild.read(["CFBundleShortVersionString": "1.2.3", "CFBundleVersion": "42"]).displayName,
                       "GlucoTrail iOS · 1.2.3 (42)")
        XCTAssertEqual(AppBuild.read([:]).number, "Unknown")
        XCTAssertEqual(AppBuild.current.version, "0.7.0")
        XCTAssertNotEqual(AppBuild.current.number, "Unknown")
    }
    func testFeedbackIncludesOnlyBuildOSAndMode() {
        let build = AppBuild(version: "1.2.3", number: "42")
        let demo = build.feedback(systemVersion: "18.0", demo: true)
        XCTAssertTrue(demo.contains("1.2.3 (42)"))
        XCTAssertTrue(demo.contains("iOS: 18.0"))
        XCTAssertTrue(demo.contains("Mode: Demo"))
        XCTAssertEqual(build.feedback(systemVersion: "18.0", demo: false),
                       demo.replacingOccurrences(of: "Mode: Demo", with: "Mode: Real sensor history"))
    }
    func testReleaseHistoryIsBundledAndMatchesInstalledVersion() throws {
        let releases = try BetaRelease.load()
        XCTAssertEqual(releases.first?.version, AppBuild.current.version)
        XCTAssertEqual(Set(releases.map(\.version)).count, releases.count)
        XCTAssertTrue(releases.allSatisfy { !$0.title.isEmpty && !$0.changes.isEmpty })
    }
    func testPrivacyManifestIsInAppBundle() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"))
        let value = try PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: Any]
        XCTAssertEqual(value?["NSPrivacyTracking"] as? Bool, false)
        XCTAssertEqual((value?["NSPrivacyCollectedDataTypes"] as? [Any])?.count, 0)
    }
}
