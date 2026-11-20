import SwiftUI

struct AppBuild: Equatable {
    let version: String
    let number: String
    var displayName: String { "GlucoTrail iOS · \(version) (\(number))" }
    static func read(_ info: [String: Any]) -> AppBuild {
        AppBuild(version: info["CFBundleShortVersionString"] as? String ?? "Unknown",
                 number: info["CFBundleVersion"] as? String ?? "Unknown")
    }
    static var current: AppBuild { read(Bundle.main.infoDictionary ?? [:]) }

    // Deliberately accepts no scanner, journal, device name or identifier.
    func feedback(systemVersion: String, demo: Bool) -> String {
        """
        \(displayName)
        iOS: \(systemVersion)
        Mode: \(demo ? "Demo" : "Real sensor history")

        What happened:

        Steps to reproduce:

        Expected result:

        No glucose readings, sensor identifiers, notes or photos are attached.
        Review anything you add before sharing. Do not include personal health information in public reports.
        """
    }
}

struct BetaRelease: Decodable, Identifiable {
    let version: String
    let title: String
    let changes: [String]
    var id: String { version }
    static func load(bundle: Bundle = .main) throws -> [BetaRelease] {
        guard let url = bundle.url(forResource: "ReleaseHistory", withExtension: "json") else {
            throw CocoaError(.fileNoSuchFile)
        }
        return try JSONDecoder().decode([BetaRelease].self, from: Data(contentsOf: url))
    }
}

struct BetaInformationView: View {
    let demo: Bool
    private let releases = try? BetaRelease.load()
    var body: some View {
        List {
            Section("Installed build") {
                Text(AppBuild.current.displayName).accessibilityIdentifier("installedBuild")
                Text("Engineering beta, not a validated medical monitor. A Bluetooth connection does not establish authenticated glucose streaming. Reception requires the foreground; alerts may be delayed or missed.")
                Text("Get beta updates through TestFlight if you were invited. This screen does not check for updates or confirm that this build is distributed through TestFlight.")
            }
            Section("Feedback") {
                NavigationLink("Prepare feedback") { BetaFeedbackView(demo: demo) }
                    .accessibilityIdentifier("prepareBetaFeedback")
                Text("Nothing is sent automatically. For a TestFlight installation, you can also use Send Beta Feedback in TestFlight. Review screenshots for health information before attaching them.")
            }
            Section("Version history") {
                if let releases {
                    ForEach(releases) { release in
                        NavigationLink("\(release.version) · \(release.title)") {
                            List(release.changes, id: \.self) { Text($0) }
                                .navigationTitle(release.version)
                        }
                    }
                } else {
                    Text("Release notes are unavailable in this build.")
                }
                Text("These notes describe development milestones, not a list of published TestFlight builds.")
            }
        }.navigationTitle("Beta information")
    }
}

struct BetaFeedbackView: View {
    let demo: Bool
    private var report: String {
        AppBuild.current.feedback(systemVersion: UIDevice.current.systemVersion, demo: demo)
    }
    var body: some View {
        List {
            Section("Review before sharing") {
                Text(report).textSelection(.enabled).accessibilityIdentifier("betaFeedbackPreview")
                ShareLink(item: report) { Label("Share feedback template", systemImage: "square.and.arrow.up") }
                    .accessibilityIdentifier("shareBetaFeedback")
            }
            Section {
                Text("The template contains only app version, iOS version and demo/real mode. Sharing opens the system sheet; you choose the recipient and finish sending there. No logs or health data are collected.")
            }
        }.navigationTitle("Feedback preview")
    }
}
