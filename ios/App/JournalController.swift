import Foundation
import Combine
import UIKit
import ImageIO
import GlucoTrailProtocol

final class JournalController: ObservableObject {
    @Published private(set) var archive = JournalArchive()
    @Published private(set) var demo = false
    @Published private(set) var error: String?
    private var store: JournalStore?
    private var demoArchive = JournalArchive()
    private var demoPhotos: [UUID: Data] = [:]
    private var storageError: String?
    var machines: [FitnessMachine] { FitnessMachine.catalogue + archive.customMachines }

    init() {
        do {
            var folder = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true).appendingPathComponent("HealthJournal", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete])
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try folder.setResourceValues(values)
            let loaded = try JournalStore(root: folder); store = loaded; archive = loaded.archive
        } catch { storageError = "Journal unavailable. Existing files were kept. \(error.localizedDescription)"; self.error = storageError }
    }
    func setDemo(_ value: Bool) {
        guard value != demo else { return }
        demo = value
        archive = value ? demoArchive : (store?.archive ?? JournalArchive())
        error = value ? nil : storageError
    }
    func save(_ entry: JournalEntry, photos: [UUID: Data]) -> Bool {
        do {
            guard entry.occurredAt <= Date() else { throw JournalError.invalid("Choose a time that has already occurred. This journal records completed events.") }
            var candidate = archive
            var saved = entry; saved.updatedAt = Date()
            if let index = candidate.entries.firstIndex(where: { $0.id == saved.id }) { candidate.entries[index] = saved }
            else { candidate.entries.append(saved) }
            try commit(candidate, photos: photos); return true
        } catch { self.error = error.localizedDescription; return false }
    }
    func delete(_ id: UUID) -> Bool {
        do {
            var candidate = archive; candidate.entries.removeAll { $0.id == id }
            try commit(candidate); return true
        } catch { self.error = error.localizedDescription; return false }
    }
    func saveMachine(_ machine: FitnessMachine) -> Bool {
        do {
            var candidate = archive
            if let index = candidate.customMachines.firstIndex(where: { $0.id == machine.id }) { candidate.customMachines[index] = machine }
            else { candidate.customMachines.append(machine) }
            try commit(candidate); return true
        } catch { self.error = error.localizedDescription; return false }
    }
    func deleteMachine(_ id: String) {
        do { var candidate = archive; candidate.customMachines.removeAll { $0.id == id }; try commit(candidate) }
        catch { self.error = error.localizedDescription }
    }
    private func commit(_ candidate: JournalArchive, photos: [UUID: Data] = [:]) throws {
        if demo {
            try candidate.validate()
            for data in photos.values { try JournalStore.validatePhoto(data) }
            guard Set(photos.keys).isSubset(of: candidate.photoIDs) else { throw JournalError.missingPhoto }
            let all = demoPhotos.merging(photos) { _, new in new }
            guard candidate.photoIDs.isSubset(of: Set(all.keys)) else { throw JournalError.missingPhoto }
            demoPhotos = all.filter { candidate.photoIDs.contains($0.key) }
            demoArchive = candidate
        } else {
            guard let store else { throw JournalError.invalid(storageError ?? "Journal unavailable") }
            try store.commit(candidate, newPhotos: photos)
        }
        archive = candidate; error = nil
    }
    func photo(_ id: UUID) throws -> Data {
        if demo { guard let data = demoPhotos[id] else { throw JournalError.missingPhoto }; return data }
        guard let store else { throw JournalError.missingPhoto }; return try store.photo(id)
    }
    static func optimizedPhoto(_ data: Data) throws -> Data {
        guard data.count <= 25_000_000 else { throw JournalError.tooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 1600,
                kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else { throw JournalError.invalid("This photo could not be decoded.") }
        // Draw pixels into a new image; source metadata (including GPS) is not copied.
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let size = CGSize(width: CGFloat(cg.width), height: CGFloat(cg.height))
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill(); context.fill(CGRect(origin: .zero, size: size))
            UIImage(cgImage: cg).draw(in: CGRect(origin: .zero, size: size))
        }
        guard let jpeg = image.jpegData(compressionQuality: 0.75) else { throw JournalError.invalid("Photo conversion failed.") }
        try JournalStore.validatePhoto(jpeg); return jpeg
    }
}
