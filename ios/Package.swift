// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "GlucoTrailProtocol",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [.library(name: "GlucoTrailProtocol", targets: ["GlucoTrailProtocol"])],
    targets: [
        .systemLibrary(name: "CSQLite", pkgConfig: "sqlite3"),
        .target(name: "GlucoTrailProtocol", dependencies: ["CSQLite"]),
        .testTarget(name: "GlucoTrailProtocolTests", dependencies: ["GlucoTrailProtocol"])
    ]
)
